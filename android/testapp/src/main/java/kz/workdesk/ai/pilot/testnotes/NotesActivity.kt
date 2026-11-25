package kz.workdesk.ai.pilot.testnotes

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Цель для сквозного теста: поле заметки (Enter сохраняет), кнопка «Сохранить», счётчик,
 * строки «баз» (касание открывает базу) и длинный хвост для прокрутки.
 */
class NotesActivity : Activity() {
    private val notes = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(48), dp(20), dp(20)) }
        root.addView(TextView(this).apply { text = "Тест-заметки"; textSize = 24f })
        val count = TextView(this).apply { text = "Заметок: 0"; textSize = 16f; contentDescription = "count" }
        val list = TextView(this).apply { textSize = 16f }
        val opened = TextView(this).apply { textSize = 16f; text = "База не открыта" }
        fun save(e: EditText) {
            val t = e.text.toString().trim()
            if (t.isEmpty()) return
            notes.add(t); e.setText("")
            count.text = "Заметок: ${notes.size}"
            list.text = notes.joinToString("\n") { "• $it" }
        }
        val note = EditText(this).apply {
            hint = "Новая заметка"; textSize = 18f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(56)
            setOnEditorActionListener { v, _, _ -> save(v as EditText); true }
        }
        root.addView(note)
        root.addView(Button(this).apply { text = "Сохранить"; setOnClickListener { save(note) } })
        root.addView(count)
        root.addView(list)
        root.addView(TextView(this).apply { text = "Базы 1С"; textSize = 20f; setPadding(0, dp(16), 0, dp(4)) })
        for (name in listOf("TEST_MAIN", "FRK_ROZNICA_PROD", "WorkTime_TEST")) {
            root.addView(TextView(this).apply {
                text = name; textSize = 18f; minHeight = dp(56); isClickable = true
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { opened.text = "Открыта база: $name" }
            })
        }
        root.addView(opened)
        for (i in 1..40) root.addView(TextView(this).apply { text = "Строка $i"; textSize = 16f; setPadding(0, dp(8), 0, dp(8)) })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
