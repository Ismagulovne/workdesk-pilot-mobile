package kz.workdesk.ai.pilot.testnotes

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Только в отладочной сборке: пишет в logcat (тег E2E), какие тексты, кнопки и поля видны и где они.
 * Нужен сквозному тесту вместо uiautomator, который приостанавливает службы специальных возможностей.
 * Тестовое приложение — всегда включён.
 */
object E2eProbe {
    private var seq = 0

    fun attach(a: Activity) {
        val root = a.window.decorView
        val h = Handler(Looper.getMainLooper())
        // Отчёт каждые 400 мс, пока окно живо: ввод текста не всегда перестраивает экран.
        val tick = object : Runnable {
            override fun run() {
                if (a.isDestroyed || a.isFinishing) return
                report(a, root)
                h.postDelayed(this, 400)
            }
        }
        h.postDelayed(tick, 200)
    }

    fun gone(a: Activity) = Log.i("E2E", "${a.packageName}|${a.localClassName}|0|${++seq}|0|1|[]")

    private fun report(a: Activity, root: View) {
        val arr = JSONArray()
        val r = Rect()
        val scr = IntArray(2)
        val win = IntArray(2)
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && v.getGlobalVisibleRect(r)) {
                v.getLocationOnScreen(scr); v.getLocationInWindow(win)
                r.offset(scr[0] - win[0], scr[1] - win[1])
                val o = JSONObject().put("t", (v.text?.toString() ?: "").take(80)).put("x1", r.left).put("y1", r.top).put("x2", r.right).put("y2", r.bottom)
                if (v is EditText) o.put("e", true).put("h", v.hint?.toString() ?: "").put("f", v.isFocused)
                arr.put(o)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        // Строка logcat ограничена ~4 КБ: делим на части по 20 узлов, тест собирает их по номеру отчёта.
        val id = ++seq
        val f = if (root.hasWindowFocus()) 1 else 0
        val parts = maxOf(1, (arr.length() + 19) / 20)
        for (i in 0 until parts) {
            val chunk = JSONArray()
            for (j in i * 20 until minOf(arr.length(), i * 20 + 20)) chunk.put(arr.get(j))
            Log.i("E2E", "${a.packageName}|${a.localClassName}|$f|$id|$i|$parts|$chunk")
        }
    }
}
