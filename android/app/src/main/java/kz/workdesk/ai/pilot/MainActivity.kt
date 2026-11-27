package kz.workdesk.ai.pilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Главный экран в стиле WorkDesk: раскрытие информации и согласие (как требует политика Accessibility
 * Google Play), затем подключение к мосту на ПК, СТОП, список запретов и журнал.
 */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pilot", MODE_PRIVATE) }
    private val ui by lazy { Ui(this) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(24)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.topBar("AI Pilot"))
            addView(ScrollView(this@MainActivity).apply { addView(root) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        E2eProbe.attach(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        PilotService.onLinkState = { render() }
    }

    override fun onPause() {
        PilotService.onLinkState = null
        super.onPause()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(ui.title("WorkDesk AI Pilot"))
        root.addView(ui.muted("Работает с Claude Code", 14f))
        if (!prefs.getBoolean("consent_v1", false)) disclosure() else controls()
    }

    /** Заметное раскрытие + явное согласие до включения службы. */
    private fun disclosure() {
        root.addView(disclosureCard(ui, onAgree = {
            prefs.edit().putBoolean("consent_v1", true).apply()
            render()
        }, onDecline = { finish() }))
    }

    private fun controls() {
        val svc = PilotService.instance
        // Уведомление со «Стоп»: спрашиваем один раз и только если разрешения нет (раньше спрашивали на каждой перерисовке).
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val service = ui.card()
        service.addView(ui.heading("1. Служба"))
        if (svc == null) {
            service.addView(ui.body("Служба выключена. Откройте «Спец. возможности» → «WorkDesk AI Pilot» и включите.", 15f))
            service.addView(ui.muted("Если переключатель серый: «Настройки → Приложения → WorkDesk AI Pilot → ⋮ → Разрешить ограниченные настройки»."))
            service.addView(ui.primary("Открыть «Спец. возможности»") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            service.addView(ui.secondary("Отозвать согласие") { prefs.edit().putBoolean("consent_v1", false).apply(); render() })
            root.addView(service)
            return
        }
        service.addView(ui.chip("Служба включена"))
        root.addView(service)

        val link = ui.card()
        link.addView(ui.heading("2. Подключение к ПК"))
        link.addView(ui.muted("Состояние: ${PilotService.linkState}", 14f))
        val url = ui.input("ws://192.168.1.10:8765/pilot", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            .apply { setText(prefs.getString("url", "")) }
        val code = ui.input("код с ПК (6 цифр)", InputType.TYPE_CLASS_NUMBER)
        link.addView(url)
        link.addView(code)
        link.addView(ui.primary("Подключить") {
            val u = url.text.toString().trim()
            if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
                Toast.makeText(this, "Адрес должен начинаться с ws:// или wss://", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putString("url", u).apply()
                svc.connect(u, code.text.toString().trim())
            }
        })
        link.addView(ui.secondary("Отключить") { svc.disconnect(); render() })
        root.addView(link)

        val control = ui.card()
        control.addView(ui.heading("3. Управление"))
        val t = svc.target
        control.addView(if (t != null) ui.chip("ИИ управляет: ${t.label}") else ui.muted("ИИ сейчас ничем не управляет", 14f))
        control.addView(ui.primary("СТОП — прекратить управление") { svc.stopByUser("главный экран"); render() })
        root.addView(control)

        val deny = ui.card()
        deny.addView(ui.heading("4. Список запретов"))
        deny.addView(ui.muted("По одной цели в строке, например название боевой базы 1С. ИИ может видеть их на экране, " +
            "но касание строки с такой целью или Enter, пока она видна, выполнится только после вашего «Да». " +
            "Список правите только вы, у ИИ такого инструмента нет."))
        val blocked = ui.input("ТОО ФРК РОЗНИЦА", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            setText((prefs.getStringSet("blocked_targets", emptySet()) ?: emptySet()).sorted().joinToString("\n"))
        }
        deny.addView(blocked)
        deny.addView(ui.primary("Сохранить список") {
            val set = blocked.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.edit().putStringSet("blocked_targets", set).apply()
            svc.journal.write("blocked_targets", "изменён человеком: ${set.size} целей")
            Toast.makeText(this, "Сохранено: ${set.size}", Toast.LENGTH_SHORT).show()
        })
        root.addView(deny)

        val log = ui.card()
        log.addView(ui.heading("Журнал"))
        log.addView(ui.mono(svc.journal.tail(15).joinToString("\n").ifEmpty { "пусто" }))
        log.addView(ui.secondary("Отозвать согласие") {
            svc.disconnect()
            prefs.edit().putBoolean("consent_v1", false).apply()
            render()
        })
        root.addView(log)
        root.addView(updatesCard())
    }

    // ---------- учётная запись WorkDesk и обновления (правила №16, №17, №33) ----------

    private val account by lazy { Account(this) }
    @Volatile private var busy = false

    private fun updatesCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.heading("Обновления"))
        val who = account.name
        card.addView(ui.muted(if (account.token != null) "Вход в WorkDesk: ${who ?: "выполнен"}" else
            "Обновления выдаются только с workdesk.kz и по допуску к WorkDesk AI. Войдите через Коммит.", 14f))
        prefs.getString("update_state", null)?.let { card.addView(ui.muted("Состояние: $it", 14f)) }
        if (account.token == null) {
            card.addView(ui.primary("Войти через Коммит") { login() })
        } else {
            card.addView(ui.primary("Проверить обновления") { checkUpdate() })
            card.addView(ui.secondary("Выйти из WorkDesk") { bg { account.logout() }; setUpdateState(null) })
        }
        card.addView(ui.muted("Нет допуска? Напишите на info@worktime.kz, что и зачем хотите делать через ИИ.", 13f))
        return card
    }

    private fun setUpdateState(s: String?) {
        prefs.edit().apply { if (s == null) remove("update_state") else putString("update_state", s) }.apply()
        runOnUiThread { if (!isFinishing) render() }
    }

    private fun bg(work: () -> Unit) {
        if (busy) return
        busy = true
        Thread { try { work() } finally { busy = false } }.start()
    }

    private fun login() = bg {
        try {
            val (url, code) = account.start()
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            setUpdateState("ждём вход в браузере…")
            setUpdateState(if (account.await(code) { isDestroyed }) null else "вход не завершён, попробуйте снова")
        } catch (e: Exception) {
            setUpdateState("вход не удался: ${e.message}")
        }
    }

    private fun checkUpdate() = bg {
        val token = account.token ?: return@bg
        try {
            setUpdateState("проверяем…")
            val offer = Updater.check(token)
            if (offer == null || !Updater.newer(offer.version, Link.VERSION)) {
                setUpdateState("установлена последняя версия ${Link.VERSION}"); return@bg
            }
            if (!Updater.canInstall(this)) {
                setUpdateState("есть версия ${offer.version}: разрешите установку из этого приложения и нажмите ещё раз")
                runOnUiThread {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                }
                return@bg
            }
            val file = Updater.download(this, token, offer) { p -> if (p % 10 == 0) setUpdateState("загрузка ${offer.version}: $p%") }
            setUpdateState("версия ${offer.version} проверена (SHA-256 и подпись WorkDesk), подтвердите установку")
            PilotService.instance?.journal?.write("update", "проверено и передано установщику: ${offer.version}")
            Updater.install(this, file)
        } catch (e: Updater.Unauthorized) {
            account.forget()
            setUpdateState("вход истёк или допуск снят — войдите снова")
        } catch (e: Exception) {
            PilotService.instance?.journal?.write("update", "отказ: ${e.message}")
            setUpdateState("не обновлено: ${e.message}")
        }
    }
}

/** Карточка раскрытия информации (отдельно, чтобы снимать её в тестах вида). */
internal fun disclosureCard(ui: Ui, onAgree: () -> Unit, onDecline: () -> Unit): LinearLayout {
    val card = ui.card()
    card.addView(ui.heading("Прежде чем начать"))
    card.addView(ui.body(
        "Это приложение использует службу специальных возможностей Android (Accessibility API), " +
            "чтобы ИИ-помощник выполнял ваши задачи на телефоне.\n\n" +
            "Что делает служба, когда вы разрешили конкретное приложение:\n" +
            "• делает снимки экрана только этого приложения;\n" +
            "• читает, какое приложение на экране и какое поле ввода в фокусе;\n" +
            "• нажимает, прокручивает и вводит текст в этом приложении.\n\n" +
            "Куда уходят данные: снимки и результаты действий передаются только на ваш компьютер, " +
            "к которому вы подключили телефон, а оттуда — в Claude (Anthropic) по вашей учетной записи Claude Code. " +
            "Разработчик приложения снимки не получает и не хранит.\n\n" +
            "Что ИИ недоступно: банки, кошельки, менеджеры паролей, системные настройки, магазин приложений, поля паролей, " +
            "окна, защищённые от снимков. На каждое приложение вы даёте отдельное разрешение.\n\n" +
            "Остановить в любой момент: красная кнопка «СТОП» вверху экрана, «Стоп» в уведомлении или плитка «Стоп ИИ».\n\n" +
            "Журнал действий хранится на телефоне 30 дней, без введённого текста.",
        15f,
    ))
    card.addView(ui.primary("Согласен, продолжить", onAgree))
    card.addView(ui.secondary("Не согласен", onDecline))
    return card
}
