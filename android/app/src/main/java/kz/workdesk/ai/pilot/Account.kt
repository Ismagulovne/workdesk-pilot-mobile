package kz.workdesk.ai.pilot

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Учётная запись WorkDesk на телефоне: вход через Коммит (единый вход, правило №17) в системном браузере,
 * как в клиенте WorkDesk: POST /api/oidc/auth → ссылка → опрос /api/oidc/auth-query до 3 минут.
 * Пароль приложение не видит. Токен нужен для обновлений и (позже) для релея /api/pilot/ws по допуску (№33).
 */
class Account(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("pilot", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val token: String? get() = prefs.getString("wd_token", null)
    val name: String? get() = prefs.getString("wd_user", null)

    /** Свой ID устройства для сервера (^[\w-]{3,64}$) и случайный uuid — не IMEI и не ANDROID_ID. */
    private val deviceId: String get() = prefs.getString("wd_device", null)
        ?: ("pilot-" + UUID.randomUUID().toString().replace("-", "").take(12)).also { prefs.edit().putString("wd_device", it).apply() }
    private val deviceUuid: String get() = prefs.getString("wd_uuid", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("wd_uuid", it).apply() }

    /** Шаг 1: ссылка для браузера и код опроса. */
    fun start(): Pair<String, String> {
        val body = JSONObject().put("op", "kommit").put("id", deviceId).put("uuid", deviceUuid)
            .put("deviceInfo", JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}".take(100)).put("os", "android"))
        val o = post("/api/oidc/auth", body)
        o.optString("error").takeIf { it.isNotEmpty() }?.let { throw Updater.UpdateError(it) }
        return Pair(o.getString("url"), o.getString("code"))
    }

    /** Шаг 2: ждать входа в браузере (до 3 минут). true — вошли. */
    fun await(code: String, cancelled: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(2000)
            val url = "${Updater.SERVER}/api/oidc/auth-query?code=$code&id=$deviceId&uuid=$deviceUuid"
            val o = http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body?.string() ?: "{}") }
            val t = o.optString("access_token")
            if (t.isNotEmpty()) {
                val u = o.optJSONObject("user")
                prefs.edit().putString("wd_token", t)
                    .putString("wd_user", u?.optString("display_name")?.ifEmpty { null } ?: u?.optString("name")).apply()
                return true
            }
            val err = o.optString("error")
            if (err.isNotEmpty() && err != "No authed oidc is found") throw Updater.UpdateError(err)
        }
        return false
    }

    fun logout() {
        val t = token
        prefs.edit().remove("wd_token").remove("wd_user").apply()
        if (t != null) runCatching {
            http.newCall(Request.Builder().url("${Updater.SERVER}/api/logout").header("Authorization", "Bearer $t")
                .post(ByteArray(0).toRequestBody(jsonType)).build()).execute().close()
        }
    }

    /** 401 от сервера: токен отозван (например, сняли допуск) — забыть его. */
    fun forget() { prefs.edit().remove("wd_token").remove("wd_user").apply() }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(Updater.SERVER + path).post(body.toString().toRequestBody(jsonType)).build()
        return http.newCall(req).execute().use { JSONObject(it.body?.string() ?: "{}") }
    }
}
