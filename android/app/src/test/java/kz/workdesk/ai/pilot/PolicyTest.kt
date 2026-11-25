package kz.workdesk.ai.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    @Test fun banksAndPasswordManagersDenied() {
        assertNotNull(Policy.appAllowed("kz.kaspi.mobile", "Kaspi.kz"))
        assertNotNull(Policy.appAllowed("kz.halyk.onlinebank", "Halyk"))
        assertNotNull(Policy.appAllowed("com.x8bit.bitwarden", "Bitwarden"))
        assertNotNull(Policy.appAllowed("com.example.x", "Мой Банк"))
        assertNotNull(Policy.appAllowed("com.android.settings", "Настройки"))
        assertNotNull(Policy.appAllowed("com.anthropic.claude", "Claude"))
        assertNotNull(Policy.appAllowed("kz.workdesk.ai.pilot", "WorkDesk AI Pilot"))
        assertNotNull(Policy.appAllowed("com.example.applepay", "ApplePay"))
    }

    @Test fun ordinaryAppsAllowed() {
        assertNull(Policy.appAllowed("com.google.android.keep", "Keep Заметки"))
        assertNull(Policy.appAllowed("com.android.chrome", "Chrome"))
        assertNull(Policy.appAllowed("com.example.display", "Display Tester"))
        assertNull(Policy.appAllowed("com.spotify.music", "Spotify"))
    }

    @Test fun cardNumbersBlocked() {
        assertTrue(Policy.hasCardNumber("4111 1111 1111 1111"))
        assertTrue(Policy.hasCardNumber("карта 4111-1111-1111-1111 спасибо"))
        assertFalse(Policy.hasCardNumber("4111 1111 1111 1112")) // неверная сумма Луна
        assertFalse(Policy.hasCardNumber("+7 701 123 45 67"))
        assertNotNull(Policy.textAllowed("4111111111111111"))
        assertNull(Policy.textAllowed("Купить хлеб и молоко"))
    }

    @Test fun tokensBlocked() {
        assertNotNull(Policy.textAllowed("sk-ant-api03-abcdefghijklmnopqrstuvwxyz"))
        assertNotNull(Policy.textAllowed("ghp_abcdefghijklmnopqrstuvwxyz0123"))
        assertNull(Policy.textAllowed("sk-short"))
    }

    @Test fun geometryFit() {
        assertEquals(Pair(576, 1280), Geometry.fit(1080, 2400))
        assertEquals(Pair(800, 600), Geometry.fit(800, 600))
        val (w, h) = Geometry.fit(1440, 3200)
        assertTrue(w * h <= Geometry.MAX_PIXELS && maxOf(w, h) <= Geometry.LONG_EDGE)
        val (x, y) = Geometry.toScreen(288.0, 640.0, 0, 0, 576.0 / 1080)
        assertEquals(540f, x, 0.5f)
        assertEquals(1200f, y, 0.5f)
    }

    @Test fun userBlockedTargets() {
        val list = setOf("ТОО ФРК РОЗНИЦА")
        assertEquals("ТОО ФРК РОЗНИЦА", Policy.findBlocked(list, listOf("Список баз", "ТОО ФРК РОЗНИЦА")))
        assertNotNull(Policy.findBlocked(list, listOf("тоо  «фрк розница» (сервер)")))
        assertNull(Policy.findBlocked(list, listOf("TEST_MAIN", "WorkTime_TEST", "ФРК РОЗНИЦА тест")))
        assertNull(Policy.findBlocked(emptySet(), listOf("ТОО ФРК РОЗНИЦА"))) // по умолчанию список пуст
        assertNull(Policy.findBlocked(setOf("  "), listOf("что угодно")))
        assertNotNull(Policy.appAllowed("com.microsoft.rdc.androidx", "Remote Desktop"))
        assertNotNull(Policy.appAllowed("kz.workdesk.app", "WorkDesk"))
    }
}
