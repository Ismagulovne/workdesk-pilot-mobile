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
    fun attach(a: Activity) {
        val root = a.window.decorView
        val h = Handler(Looper.getMainLooper())
        val r = Runnable { report(a, root) }
        fun post() { h.removeCallbacks(r); h.postDelayed(r, 150) }
        root.viewTreeObserver.addOnGlobalLayoutListener { post() }
        root.viewTreeObserver.addOnScrollChangedListener { post() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> post() }
        root.viewTreeObserver.addOnWindowFocusChangeListener { post() }
    }

    fun gone(a: Activity) = Log.i("E2E", "${a.packageName}|${a.localClassName}|0|[]")

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
                val o = JSONObject().put("t", (v.text?.toString() ?: "").take(120)).put("x1", r.left).put("y1", r.top).put("x2", r.right).put("y2", r.bottom)
                if (v is EditText) o.put("e", true).put("h", v.hint?.toString() ?: "").put("f", v.isFocused)
                arr.put(o)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        Log.i("E2E", "${a.packageName}|${a.localClassName}|${if (root.hasWindowFocus()) 1 else 0}|$arr")
    }
}
