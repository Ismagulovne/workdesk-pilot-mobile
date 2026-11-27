package kz.workdesk.ai.pilot

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.CompletableFuture

/**
 * Окно согласия на телефоне: доступ к приложению (select_window) и действия с целями из списка запретов.
 *
 * Показывается оверлеем службы специальных возможностей (TYPE_ACCESSIBILITY_OVERLAY), а не отдельным экраном:
 * с Android 14 запуск Activity из службы, пока на экране чужое приложение, блокируется (BAL_BLOCK),
 * и человек не видел вопроса. Оверлей не зависит от этих правил и остаётся поверх выбранного приложения.
 * Пока окно открыто, исполнитель ждёт ответа и других действий не выполняет (один поток), поэтому ИИ
 * не может сам нажать «Разрешить».
 */
object Consent {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: CompletableFuture<Boolean>? = null
    private var shown: View? = null

    /** Разрешение управлять приложением. Тексты — ru/kk/en по языку телефона (res/values*). */
    fun ask(svc: PilotService, label: String, answer: CompletableFuture<Boolean>) = show(
        svc, svc.getString(R.string.consent_ask_title), askText(svc, label), svc.getString(R.string.consent_allow), answer,
    )

    internal fun askText(ctx: Context, label: String) = ctx.getString(R.string.consent_ask_text, label)

    /** Подтверждение одного действия, которое может открыть цель из списка запретов пользователя. */
    fun confirmBlocked(svc: PilotService, target: String, action: String, answer: CompletableFuture<Boolean>) = show(
        svc, svc.getString(R.string.consent_blocked_title, target),
        svc.getString(R.string.consent_blocked_text, action, target),
        svc.getString(R.string.consent_yes_once), answer,
    )

    /** Служба выключается — открытый вопрос считается отказом. */
    fun cancel() {
        pending?.complete(false)
    }

    private fun show(svc: PilotService, title: String, text: String, yes: String, answer: CompletableFuture<Boolean>) {
        pending?.complete(false)
        pending = answer
        // Любой исход (ответ, тайм-аут исполнителя, стоп службы) закрывает окно.
        answer.whenComplete { _, _ -> main.post { if (pending === answer) pending = null; hide(svc) } }
        main.post {
            if (answer.isDone) return@post
            hide(svc)
            val ui = Ui(svc)
            val card = consentView(svc, title, text, yes, onDeny = { answer.complete(false) }, onAllow = { answer.complete(true) })
            card.background = GradientDrawable().apply {
                setColor(svc.getColor(R.color.wd_surface)); cornerRadius = ui.dp(14).toFloat()
            }
            card.clipToOutline = true
            val scroll = ScrollView(svc).apply { addView(card) }
            val scrim = object : FrameLayout(svc) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) answer.complete(false)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                // Затемнение из токена текста WorkDesk; касание мимо окна ничего не делает.
                setBackgroundColor((svc.getColor(R.color.wd_text) and 0x00FFFFFF) or (0x99 shl 24))
                isClickable = true
                isFocusableInTouchMode = true
                val side = ui.dp(16)
                addView(scroll, FrameLayout.LayoutParams(
                    minOf(ui.dp(480), svc.resources.displayMetrics.widthPixels - 2 * side),
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ))
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { svc.getSystemService(WindowManager::class.java).addView(scrim, lp) }
                .onFailure { answer.complete(false); return@post }
            scrim.requestFocus()
            shown = scrim
            E2eProbe.attachOverlay(scrim, svc.packageName, "Consent") { shown === scrim }
        }
    }

    private fun hide(svc: PilotService) {
        val v = shown ?: return
        shown = null
        runCatching { svc.getSystemService(WindowManager::class.java).removeView(v) }
        E2eProbe.gone(svc.packageName, "Consent")
    }
}

/** Содержимое окна согласия в стиле WorkDesk (отдельно, чтобы снимать его в тестах вида). */
internal fun consentView(ctx: Context, title: String, text: String, yes: String, onDeny: () -> Unit, onAllow: () -> Unit): LinearLayout {
    val ui = Ui(ctx)
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ctx.getColor(R.color.wd_surface))
        setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16))
    }
    root.addView(ui.chip("WorkDesk AI Pilot"))
    root.addView(ui.heading(title).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
    root.addView(ui.body(text, 15f))
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0) }
    fun weighted(start: Int) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = start; topMargin = ui.dp(10) }
    row.addView(ui.secondary(ctx.getString(R.string.consent_deny), onDeny), weighted(0))
    row.addView(ui.primary(yes, onAllow), weighted(ui.dp(10)))
    root.addView(row)
    return root
}
