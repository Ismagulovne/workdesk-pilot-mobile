package kz.workdesk.ai.pilot

import android.app.Activity
import android.view.View

/** Релиз: датчик для сквозных тестов отключён. */
object E2eProbe {
    fun attach(a: Activity) {}
    fun attachOverlay(root: View, pkg: String, name: String, alive: () -> Boolean) {}
    fun gone(a: Activity) {}
    fun gone(pkg: String, name: String) {}
}
