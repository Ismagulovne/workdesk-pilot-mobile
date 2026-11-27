package kz.workdesk.ai.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdaterTest {
    private val cert = Updater.PILOT_CERT_SHA256
    private val offer = Updater.Offer("0.2.0", "https://workdesk.kz/download/workdesk-ai-pilot-0.2.0-android.apk", "ab".repeat(32), cert, 100)
    private fun v(
        sha: String = "ab".repeat(32), pkg: String? = "kz.workdesk.ai.pilot", code: Long = 2, installed: Long = 1,
        certs: List<String> = listOf(cert), mine: List<String> = listOf(cert), o: Updater.Offer = offer,
    ) = Updater.verdict(o, sha, pkg, code, installed, certs, mine, "kz.workdesk.ai.pilot")

    @Test fun versions() {
        assertTrue(Updater.newer("0.2.0", "0.1.0"))
        assertTrue(Updater.newer("0.1.10", "0.1.9"))
        assertTrue(Updater.newer("1.0", "0.9.9"))
        assertFalse(Updater.newer("0.1.0", "0.1.0"))
        assertFalse(Updater.newer("0.1.0-beta", "0.1.0"))
        assertFalse(Updater.newer("", "0.1.0"))
        assertFalse(Updater.newer("abc", "0.1.0"))
    }

    @Test fun goodUpdatePasses() = assertNull(v())

    @Test fun colonFingerprintAccepted() {
        val colon = cert.chunked(2).joinToString(":").uppercase()
        assertNull(v(certs = listOf(colon)))
    }

    @Test fun tamperedFileRejected() = assertNotNull(v(sha = "cd".repeat(32)))
    @Test fun otherPackageRejected() = assertNotNull(v(pkg = "com.evil.app"))
    @Test fun brokenApkRejected() = assertNotNull(v(pkg = null, code = -1, certs = emptyList()))
    @Test fun downgradeRejected() = assertNotNull(v(code = 1, installed = 1))
    @Test fun foreignSignatureRejected() = assertNotNull(v(certs = listOf("00".repeat(32))))
    @Test fun multipleSignersRejected() = assertNotNull(v(certs = listOf(cert, "00".repeat(32))))
    @Test fun serverSendsForeignCertRejected() = assertNotNull(v(o = offer.copy(certSha256 = "11".repeat(32))))
    @Test fun debugInstallNotUpdatedByRelease() = assertNotNull(v(mine = listOf("22".repeat(32))))

    @Test fun parseOffer() {
        val o = Updater.parseOffer("""{"platform":"android","version":"0.2.0","sha256":"AB${"ab".repeat(31)}",
            "url":"https://workdesk.kz/download/x.apk","cert_sha256":"${cert.chunked(2).joinToString(":")}","size":5}""")!!
        assertEquals("0.2.0", o.version)
        assertEquals("ab".repeat(32), o.sha256)
        assertEquals(cert, o.certSha256)
    }

    @Test fun parseOfferNoAccessOrGarbage() {
        assertNull(Updater.parseOffer("""{"platform":"android","page":"https://workdesk.kz/pilot/"}"""))
        assertNull(Updater.parseOffer("не json"))
        assertNull(Updater.parseOffer(""))
        assertNull(Updater.parseOffer("""{"version":"9","sha256":"ab","url":"https://evil.example/x.apk"}"""))
        assertNull(Updater.parseOffer("""{"version":"9","sha256":"ab","url":"https://workdesk.kz.evil.example/x.apk"}"""))
    }

    @Test fun sha256OfFile() {
        val f = File.createTempFile("upd", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
    }

    @Test fun stressManyChecks() {
        // Нагрузка: 100 000 решений подряд без ошибок и без ложных «да».
        repeat(100_000) { i ->
            val bad = i % 2 == 1
            val r = v(sha = if (bad) "%064x".format(i) else "ab".repeat(32))
            if (bad) assertNotNull(r) else assertNull(r)
        }
    }
}
