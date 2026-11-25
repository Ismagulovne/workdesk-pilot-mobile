package kz.workdesk.ai.pilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Главный экран: раскрытие информации и согласие (как требует политика Accessibility Google Play),
 * затем подключение к мосту на ПК.
 */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pilot", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
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

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, pad / 2, 0, pad / 2)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }

    private fun render() {
        root.removeAllViews()
        root.addView(text("WorkDesk AI Pilot", 22f, true))
        root.addView(text("Работает с Claude Code", 13f))
        if (!prefs.getBoolean("consent_v1", false)) disclosure() else controls()
    }

    /** Заметное раскрытие + явное согласие до включения службы. */
    private fun disclosure() {
        root.addView(text("Прежде чем начать", 18f, true))
        root.addView(text(
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
        ))
        root.addView(button("Согласен, продолжить") {
            prefs.edit().putBoolean("consent_v1", true).apply()
            render()
        })
        root.addView(button("Не согласен") { finish() })
    }

    private fun controls() {
        val svc = PilotService.instance
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        root.addView(text("1. Служба", 17f, true))
        if (svc == null) {
            root.addView(text("Служба выключена. Откройте «Спец. возможности» → «WorkDesk AI Pilot» и включите. " +
                "Если переключатель серый: «Настройки → Приложения → WorkDesk AI Pilot → ⋮ → Разрешить ограниченные настройки»."))
            root.addView(button("Открыть «Спец. возможности»") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            root.addView(button("Отозвать согласие") { prefs.edit().putBoolean("consent_v1", false).apply(); render() })
            return
        }
        root.addView(text("Служба включена."))

        root.addView(text("2. Подключение к ПК", 17f, true))
        root.addView(text("Состояние: ${PilotService.linkState}"))
        val url = EditText(this).apply {
            hint = "ws://192.168.1.10:8765/pilot"
            setText(prefs.getString("url", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val code = EditText(this).apply { hint = "код с ПК (6 цифр)"; inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(url)
        root.addView(code)
        root.addView(button("Подключить") {
            val u = url.text.toString().trim()
            if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
                Toast.makeText(this, "Адрес должен начинаться с ws:// или wss://", Toast.LENGTH_LONG).show(); return@button
            }
            prefs.edit().putString("url", u).apply()
            svc.connect(u, code.text.toString().trim())
        })
        root.addView(button("Отключить") { svc.disconnect(); render() })

        root.addView(text("3. Управление", 17f, true))
        val t = svc.target
        root.addView(text(if (t != null) "ИИ управляет: ${t.label}" else "ИИ сейчас ничем не управляет"))
        root.addView(button("СТОП — прекратить управление") { svc.stopByUser("главный экран"); render() })

        root.addView(text("4. Список запретов", 17f, true))
        root.addView(text("По одной цели в строке, например название боевой базы 1С. ИИ может видеть их на экране, " +
            "но касание строки с такой целью или Enter, пока она видна, выполнится только после вашего «Да». " +
            "Список правите только вы, у ИИ такого инструмента нет.", 13f))
        val blocked = EditText(this).apply {
            hint = "ТОО ФРК РОЗНИЦА"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText((prefs.getStringSet("blocked_targets", emptySet()) ?: emptySet()).sorted().joinToString("\n"))
        }
        root.addView(blocked)
        root.addView(button("Сохранить список") {
            val set = blocked.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.edit().putStringSet("blocked_targets", set).apply()
            svc.journal.write("blocked_targets", "изменён человеком: ${set.size} целей")
            Toast.makeText(this, "Сохранено: ${set.size}", Toast.LENGTH_SHORT).show()
        })

        root.addView(text("Журнал (последние записи)", 17f, true))
        root.addView(text(svc.journal.tail(15).joinToString("\n").ifEmpty { "пусто" }, 11f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        root.addView(button("Отозвать согласие") {
            svc.disconnect()
            prefs.edit().putBoolean("consent_v1", false).apply()
            render()
        })
    }
}
