package kz.workdesk.ai.pilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CompletableFuture

/** Окно согласия на телефоне: доступ к приложению (select_window) и действия с целями из списка запретов. */
class ConsentActivity : Activity() {
    companion object {
        @Volatile private var pending: CompletableFuture<Boolean>? = null
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_YES = "yes"

        /** Разрешение управлять приложением. */
        fun ask(ctx: Context, label: String, answer: CompletableFuture<Boolean>) = show(
            ctx, "Разрешить ИИ?",
            "ИИ-помощник (работает с Claude Code на вашем компьютере) просит управлять приложением «$label».\n\n" +
                "Он будет видеть экран только этого приложения, нажимать, прокручивать и вводить текст. " +
                "Пароли, оплату и подтверждения выполняете вы.\n\n" +
                "Остановить: красная кнопка «СТОП» вверху экрана, кнопка «Стоп» в уведомлении или плитка «Стоп ИИ».",
            "Разрешить", answer,
        )

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
        setFinishOnTouchOutside(false)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Разрешить ИИ?"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            textSize = 16f
            text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, 0) }
        row.addView(Button(this).apply { text = "Отказать"; setOnClickListener { answer(false) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = intent.getStringExtra(EXTRA_YES) ?: "Разрешить"; setOnClickListener { answer(true) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = answer(false)

    override fun onDestroy() {
        if (isFinishing) { pending?.complete(false); pending = null }
        super.onDestroy()
    }
}
