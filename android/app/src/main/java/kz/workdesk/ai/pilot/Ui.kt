package kz.workdesk.ai.pilot

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Элементы интерфейса в стиле WorkDesk (workdesk.kz, console/public/site.css):
 * фиолетовая шапка с логотипом на белой плашке, кнопки со скруглением 14 и жирным текстом,
 * карточки на surface с рамкой border. Цвета — только из res/values/colors.xml (токены WorkDesk).
 */
class Ui(private val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    fun c(id: Int) = ctx.getColor(id)

    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    private fun round(fill: Int, radius: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun ripple(bg: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(c(R.color.wd_brand_600)), bg, null)

    /** Шапка как .top на workdesk.kz. */
    fun topBar(subtitle: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(c(R.color.wd_brand))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_pilot) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(TextView(ctx).apply {
            text = "WorkDesk"; typeface = bold; setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(dp(10), 0, 0, 0)
        })
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(TextView(ctx).apply {
            text = subtitle; typeface = regular; setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
    }

    fun title(s: String) = TextView(ctx).apply {
        text = s; typeface = bold; setTextColor(c(R.color.wd_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setPadding(0, dp(4), 0, dp(4))
    }

    fun heading(s: String) = TextView(ctx).apply {
        text = s; typeface = bold; setTextColor(c(R.color.wd_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPadding(0, 0, 0, dp(6))
    }

    fun body(s: String, size: Float = 16f) = TextView(ctx).apply {
        text = s; typeface = regular; setTextColor(c(R.color.wd_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setLineSpacing(0f, 1.35f); setPadding(0, dp(4), 0, dp(4))
    }

    fun muted(s: String, size: Float = 13f) = body(s, size).apply { setTextColor(c(R.color.wd_muted)) }

    /** Метка-пилюля, как .chip: brand-50 фон, текст brand. */
    fun chip(s: String) = TextView(ctx).apply {
        text = s; typeface = bold; setTextColor(c(R.color.wd_link))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = round(c(R.color.wd_brand_50), 999)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) }
    }

    /** Карточка: surface, рамка border, скругление 14. */
    fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c(R.color.wd_surface), 14, c(R.color.wd_border))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    /** Основная кнопка .btn: brand, белый жирный текст, скругление 14. */
    fun primary(s: String, onClick: () -> Unit) = button(s, onClick, c(R.color.wd_brand), 0xFFFFFFFF.toInt(), c(R.color.wd_brand))

    /** Вторичная кнопка, как на workdesk.kz: контур 2dp и текст цвета ссылки (brand, в тёмной теме #A996FF). */
    fun secondary(s: String, onClick: () -> Unit) = button(s, onClick, c(R.color.wd_surface), c(R.color.wd_link), c(R.color.wd_link))

    private fun button(s: String, onClick: () -> Unit, fill: Int, fg: Int, stroke: Int) = TextView(ctx).apply {
        text = s; typeface = bold; setTextColor(fg); gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isClickable = true; isFocusable = true
        minHeight = dp(48)
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = ripple(round(fill, 14, stroke, 2))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
        this.hint = hint; inputType = type; typeface = regular
        setTextColor(c(R.color.wd_text)); setHintTextColor(c(R.color.wd_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = round(c(R.color.wd_surface), 10, c(R.color.wd_border))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    fun mono(s: String) = body(s, 12f).apply {
        typeface = Typeface.MONOSPACE; setTextColor(c(R.color.wd_muted))
        background = round(c(R.color.wd_brand_50), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
}
