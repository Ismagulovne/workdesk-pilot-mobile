package kz.workdesk.ai.pilot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Обновление исполнителя вне Google Play (правила №14 и №16): только с сервера WorkDesk и только после проверки.
 *
 * 1. GET /api/workdesk/update?platform=android&edition=pilot с токеном WorkDesk (Bearer) — есть ли версия новее.
 * 2. Файл скачивается с тем же токеном (WorkDesk AI выдаётся по допуску, правило №33).
 * 3. До установки: SHA-256 файла совпадает с ответом сервера; APK — того же пакета, версия выше установленной;
 *    сертификат подписи APK совпадает с зашитым в приложение [PILOT_CERT_SHA256] и с сертификатом установленной
 *    копии. Отпечаток из ответа сервера тоже сверяется, но решает зашитый: подмена сервера не поможет.
 * 4. Установка — через PackageInstaller, Android сам спросит человека «Обновить?».
 * При любом несовпадении файл удаляется и ничего не ставится.
 */
object Updater {
    /** SHA-256 сертификата подписи релизов WorkDesk AI Pilot (алиас pilot, сертификат в секретнице). */
    const val PILOT_CERT_SHA256 = "b50f03c77e86307a9ce0a6ee949f3040580c4dbc3205cdfb965ac5f0bff0554a"
    const val SERVER = "https://workdesk.kz"
    private const val ACTION_INSTALLED = "kz.workdesk.ai.pilot.UPDATE_STATUS"

    data class Offer(val version: String, val url: String, val sha256: String, val certSha256: String, val size: Long)

    open class UpdateError(msg: String) : Exception(msg)
    class Unauthorized : UpdateError("401")

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    // ---------- чистые проверки (юнит-тесты: UpdaterTest) ----------

    /** "0.1.10" > "0.1.9"; лишние части и суффиксы (-beta) не ломают сравнение. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().split('.', '-', '+').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Отпечаток к виду "b50f…": без двоеточий и пробелов, строчными. */
    fun normHex(s: String) = s.replace(":", "").replace(" ", "").lowercase()

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    /** Разбор ответа сервера; null — обновления нет (или нет допуска: сервер тогда не отдаёт url). */
    fun parseOffer(json: String): Offer? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val url = o.optString("url"); val ver = o.optString("version"); val sha = o.optString("sha256")
        if (url.isEmpty() || ver.isEmpty() || sha.isEmpty()) return null
        if (!url.startsWith("$SERVER/")) return null // файл только с нашего сервера
        return Offer(ver, url, normHex(sha), normHex(o.optString("cert_sha256")), o.optLong("size", -1))
    }

    /**
     * Решение по скачанному файлу. Возвращает null, если всё сходится, иначе причину отказа.
     * certs — отпечатки сертификатов подписи APK; installedCerts — установленной копии.
     */
    fun verdict(
        offer: Offer, fileSha: String, apkPackage: String?, apkVersionCode: Long, installedVersionCode: Long,
        certs: List<String>, installedCerts: List<String>, myPackage: String,
    ): String? {
        if (fileSha != offer.sha256) return "контрольная сумма файла не совпала с сервером"
        if (apkPackage != myPackage) return "файл не является обновлением WorkDesk AI Pilot ($apkPackage)"
        if (apkVersionCode <= installedVersionCode) return "версия в файле не новее установленной"
        val c = certs.map(::normHex)
        if (c.size != 1 || c[0] != PILOT_CERT_SHA256) return "подпись файла не WorkDesk (сертификат не совпал)"
        if (offer.certSha256.isNotEmpty() && offer.certSha256 != PILOT_CERT_SHA256) return "сервер прислал чужой отпечаток подписи"
        if (installedCerts.map(::normHex) != c) return "установленная копия подписана другим ключом (отладочная сборка?) — обновите вручную с workdesk.kz/pilot"
        return null
    }

    // ---------- сеть и установка ----------

    fun check(token: String): Offer? {
        val req = Request.Builder().url("$SERVER/api/workdesk/update?platform=android&edition=pilot")
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw Unauthorized()
            if (!r.isSuccessful) throw UpdateError("сервер ответил ${r.code}")
            return parseOffer(r.body?.string() ?: "")
        }
    }

    /** Скачивает и проверяет; при успехе — файл, готовый к установке. */
    fun download(ctx: Context, token: String, offer: Offer, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "pilot-${offer.version}.apk")
        val req = Request.Builder().url(offer.url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw UpdateError(if (r.code == 404) "файл недоступен (нет допуска к WorkDesk AI?)" else "загрузка: ${r.code}")
            val body = r.body ?: throw UpdateError("пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: offer.size
            if (total > 200L shl 20) throw UpdateError("файл слишком большой")
            body.byteStream().use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done > 200L shl 20) throw UpdateError("файл слишком большой")
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                    }
                }
            }
        }
        val reason = runCatching { verifyFile(ctx, offer, file) }.getOrElse { "файл повреждён: ${it.message}" }
        if (reason != null) { file.delete(); throw UpdateError(reason) }
        return file
    }

    private fun verifyFile(ctx: Context, offer: Offer, file: File): String? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags)
        val me = pm.getPackageInfo(ctx.packageName, flags)
        return verdict(
            offer, sha256(file), apk?.packageName, apk?.longVersionCode ?: -1, me.longVersionCode,
            certsOf(apk), certsOf(me), ctx.packageName,
        )
    }

    private fun certsOf(p: PackageInfo?): List<String> {
        val si = p?.signingInfo ?: return emptyList()
        // Несколько подписантов не принимаем; при ротации ключа — текущий сертификат.
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        return sigs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    /** Нужно разрешение «Установка неизвестных приложений» для нас; false — открыть настройки. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("pilot.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, UpdateStatusReceiver::class.java).setAction(ACTION_INSTALLED)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Ответ установщика: попросить человека подтвердить обновление или показать ошибку. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится новой версией
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка установки"
                ctx.getSharedPreferences("pilot", Context.MODE_PRIVATE).edit().putString("update_state", "не установлено: $msg").apply()
            }
        }
    }
}
