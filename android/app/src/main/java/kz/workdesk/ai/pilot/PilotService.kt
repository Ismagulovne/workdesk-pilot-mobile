package kz.workdesk.ai.pilot

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Служба специальных возможностей — исполнитель действий ИИ.
 * Держит: выбранное приложение, флаг «Стоп», индикатор поверх экрана, уведомление, связь с мостом.
 */
class PilotService : AccessibilityService() {
    companion object {
        @Volatile var instance: PilotService? = null
        @Volatile var linkState: String = "не подключено"
        var onLinkState: ((String) -> Unit)? = null

        const val STOPPED = "Остановлено пользователем (кнопка «Стоп» на телефоне). Управление отозвано. " +
            "Прекратите действия и спросите человека, продолжать ли; продолжить можно только после нового разрешения (select_window)."
        private const val CHANNEL = "pilot"
        private const val NOTIF_ID = 1
    }

    val stopped = AtomicBoolean(false)
    @Volatile var target: Target? = null
        private set
    val overlayRect = Rect()
    lateinit var journal: Journal
        private set
    lateinit var executor: Executor
        private set
    private var link: Link? = null
    private var overlay: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        journal = Journal(this)
        executor = Executor(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ИИ управляет приложением", NotificationManager.IMPORTANCE_LOW))
        journal.write("service", "включена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        release("служба выключена")
        link?.close()
        instance = null
        super.onDestroy()
    }

    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString()

    // ---------- связь ----------

    fun connect(url: String, code: String) {
        link?.close()
        link = Link(this, url, code) { s ->
            linkState = s
            main.post { onLinkState?.invoke(s) }
        }.also { it.open() }
    }

    fun disconnect() {
        link?.close()
        link = null
        release("отключено человеком")
        linkState = "не подключено"
        onLinkState?.invoke(linkState)
    }

    // ---------- разрешение и стоп ----------

    fun grant(t: Target) {
        stopped.set(false)
        target = t
        journal.write("grant", t.pkg)
        main.post { showIndicator(t.label) }
    }

    fun release(why: String) {
        if (target != null) journal.write("release", why)
        target = null
        main.post { hideIndicator() }
    }

    /** Мгновенный стоп: кнопка на экране, уведомление или плитка. */
    fun stopByUser(source: String) {
        stopped.set(true)
        target = null
        journal.write("stop", source)
        link?.sendStopped()
        main.post { hideIndicator() }
    }

    // ---------- индикатор и уведомление ----------

    private fun showIndicator(label: String) {
        hideIndicator()
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            text = "● ИИ: $label   ■ СТОП"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xEEC62828.toInt())
            textSize = 12f
            setPadding(24, 8, 24, 8)
            contentDescription = "Остановить ИИ"
            setOnClickListener { stopByUser("кнопка на экране") }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8; y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(tv, lp)
        overlay = tv
        tv.post {
            val loc = IntArray(2)
            tv.getLocationOnScreen(loc)
            // Запас 24 px вокруг кнопки, чтобы ИИ не попал в неё случайно.
            overlayRect.set(loc[0] - 24, loc[1] - 24, loc[0] + tv.width + 24, loc[1] + tv.height + 24)
        }

        val stopPi = PendingIntent.getBroadcast(this, 0, Intent(this, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stop)
            .setColor(getColor(R.color.wd_brand))
            .setContentTitle("ИИ управляет: $label")
            .setContentText("Нажмите «Стоп», чтобы сразу прекратить")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Стоп", stopPi).build())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
        TileService.requestListeningState(this, ComponentName(this, StopTileService::class.java))
    }

    private fun hideIndicator() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        overlayRect.setEmpty()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        TileService.requestListeningState(this, ComponentName(this, StopTileService::class.java))
    }
}
