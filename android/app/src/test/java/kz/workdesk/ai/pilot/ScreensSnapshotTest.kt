package kz.workdesk.ai.pilot

import android.widget.LinearLayout
import android.widget.ScrollView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Снимки настоящих экранов приложения (те же функции, что в MainActivity и окне согласия Consent),
 * чтобы сверить вид со стилем WorkDesk без телефона. Картинки — в артефакте CI.
 */
abstract class ScreensBase(night: Boolean) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT),
        theme = "Theme.WorkDesk",
    )

    @Test fun disclosure() {
        val ctx = paparazzi.context
        val ui = Ui(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(24))
            addView(ui.title("WorkDesk AI Pilot"))
            addView(ui.muted("Работает с Claude Code", 14f))
            addView(disclosureCard(ui, {}, {}))
        }
        paparazzi.snapshot(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.wd_bg))
            addView(ui.topBar("AI Pilot"))
            addView(ScrollView(ctx).apply { addView(content) })
        })
    }

    @Test fun consent() {
        val ctx = paparazzi.context
        paparazzi.snapshot(consentView(ctx, "Разрешить ИИ?", Consent.askText("Google Keep"), "Разрешить", {}, {}))
    }

    @Test fun blockedTarget() {
        val ctx = paparazzi.context
        paparazzi.snapshot(consentView(ctx, "Открыть «ТОО ФРК РОЗНИЦА»?",
            "ИИ хочет выполнить действие (нажатие), которое может открыть «ТОО ФРК РОЗНИЦА».\n\n" +
                "Эта цель в вашем списке запретов. Разрешайте, только если вы сами просили это сделать.",
            "Да, один раз", {}, {}))
    }
}

class ScreensLightTest : ScreensBase(night = false)
class ScreensDarkTest : ScreensBase(night = true)
