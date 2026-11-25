package kz.workdesk.ai.pilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.LinearLayout
import java.util.concurrent.CompletableFuture

/** Окно согласия на телефоне: доступ к приложению (select_window) и действия с целями из списка запретов. */
class ConsentActivity : Activity() {
    companion object {
        @Volatile private var pending: CompletableFuture<Boolean>? = null
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_YES = "yes"

        /** Разрешение управлять приложением. */
        fun ask(ctx: Context, label: String, answer: CompletableFuture<Boolean>) =
            show(ctx, "Разрешить ИИ?", askText(label), "Разрешить", answer)

        internal fun askText(label: String) =
            "ИИ-помощник (работает с Claude Code на вашем компьютере) просит управлять приложением «$label».\n\n" +
                "Он будет видеть экран только этого приложения, нажимать, прокручивать и вводить текст. " +
                "Пароли, оплату и подтверждения выполняете вы.\n\n" +
                "Остановить: красная кнопка «СТОП» вверху экрана, кнопка «Стоп» в уведомлении или плитка «Стоп ИИ»."

        /** Подтверждение одного действия, которое может открыть цель из списка запретов пользователя. */
        fun confirmBlocked(ctx: Context, target: String, action: String, answer: CompletableFuture<Boolean>) = show(
            ctx, "Открыть «$target»?",
            "ИИ хочет выполнить действие ($action), которое может открыть «$target».\n\n" +
                "Эта цель в вашем списке запретов. Разрешайте, только если вы сами просили это сделать.",
            "Да, один раз", answer,
        )

        private fun show(ctx: Context, title: String, text: String, yes: String, answer: CompletableFuture<Boolean>) {
            pending?.complete(false)
            pending = answer
            ctx.startActivity(
                Intent(ctx, ConsentActivity::class.java)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_YES, yes)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            )
        }
    }

    private fun answer(v: Boolean) {
        pending?.complete(v)
        pending = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setFinishOnTouchOutside(false)
        val root = consentView(this, intent.getStringExtra(EXTRA_TITLE) ?: "Разрешить ИИ?",
            intent.getStringExtra(EXTRA_TEXT) ?: "", intent.getStringExtra(EXTRA_YES) ?: "Разрешить",
            onDeny = { answer(false) }, onAllow = { answer(true) })
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = answer(false)

    override fun onDestroy() {
        if (isFinishing) { pending?.complete(false); pending = null }
        super.onDestroy()
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
    row.addView(ui.secondary("Отказать", onDeny), weighted(0))
    row.addView(ui.primary(yes, onAllow), weighted(ui.dp(10)))
    root.addView(row)
    return root
}
