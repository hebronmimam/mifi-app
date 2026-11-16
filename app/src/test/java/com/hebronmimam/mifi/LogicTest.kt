package com.hebronmimam.mifi

import com.hebronmimam.mifi.core.Hash
import com.hebronmimam.mifi.device.Mifi
import com.hebronmimam.mifi.device.MifiScheme
import com.hebronmimam.mifi.device.Router
import com.hebronmimam.mifi.update.Updater
import com.hebronmimam.mifi.usage.Attribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LogicTest {

    @Test fun counterDeltaHandlesResets() {
        assertNull(Attribution.delta(null, 100))
        assertEquals(50L, Attribution.delta(100, 150))
        // Monthly roll-over or reconnect: the counter restarted, so everything since counts.
        assertEquals(30L, Attribution.delta(1000, 30))
    }

    @Test fun rangeIsExactWhenOneDeviceIsAlone() {
        val one = Attribution.split(box = 1000, phone = 200, others = listOf("A"))
        assertEquals(listOf(Attribution.Share("A", 800, 800)), one)
        val two = Attribution.split(box = 1000, phone = 200, others = listOf("A", "B"))
        assertEquals(listOf(Attribution.Share("A", 0, 800), Attribution.Share("B", 0, 800)), two)
        // The phone can't have used more than the box carried.
        assertEquals(0L, Attribution.split(100, 500, listOf("A"))[0].hi)
    }

    @Test fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.sha256("abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hash.md5("abc"))
    }

    @Test fun schemeReadFromZteScripts() {
        val js = """
            var config = { WEB_ATTR_IF_SUPPORT_SHA256: 2, PASSWORD_ENCODE: true };
            function login(pwd) {
              var LD = getLD();
              var hash = SHA256(SHA256(pwd).toUpperCase() + LD).toUpperCase();
              var params = { isTest: false, goformId: "LOGIN", password: hash };
            }
            function getAD() {
              var a = hex_md5(rd0.wa_inner_version + rd0.cr_version);
              return hex_md5(a + rd);
            }
        """.trimIndent()
        val s = MifiScheme.analyze(js)
        assertEquals(2, s.login)
        assertTrue(s.upper)
        assertEquals("md5", s.ad)
        assertFalse(s.loginAd)
        assertTrue(s.detected)
    }

    @Test fun schemeLowerCaseWhenScriptsDontUpperCase() {
        val js = """
            WEB_ATTR_IF_SUPPORT_SHA256: "2",
            function login(p){ var h = sha256(sha256(p) + ld); post({goformId:"LOGIN", password:h, AD: ad}); }
            var ad = paswordAlgorithmsCookie(paswordAlgorithmsCookie(x.wa_inner_version + x.cr_version) + rd)
        """.trimIndent()
        val s = MifiScheme.analyze(js)
        assertFalse(s.upper)
        assertTrue(s.loginAd)
    }

    @Test fun loginHashFollowsScheme() {
        val upper = MifiScheme(2, true, "md5", false, true)
        val expect = Hash.sha256(Hash.sha256("pw").uppercase() + "LD1").uppercase()
        assertEquals(expect, upper.loginPassword("pw", "LD1"))
        val ad = MifiScheme(2, true, "md5", false, true).ad("W", "C", "R")
        assertEquals(Hash.md5(Hash.md5("WC") + "R"), ad)
    }

    @Test fun ucs2RoundTrip() {
        val text = "Bal: ₦250.00 — ok"
        assertEquals(text, Mifi.ucs2Decode(Mifi.ucs2Encode(text)))
        assertEquals("0048006900", Mifi.ucs2Encode("Hi\u0000").take(10))
        assertTrue(Mifi.isGsm7("Hello @ 123"))
        assertFalse(Mifi.isGsm7("₦"))
    }

    @Test fun mifiDateParses() {
        val t = Mifi.parseDate("26,11,16,14,05,09,+4")
        val c = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, c.get(Calendar.MONTH))
        assertEquals(16, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0L, Mifi.parseDate("garbage"))
    }

    @Test fun routerSmsLineParses() {
        val m = Router.parseSmsLine("7 0 +234$803 2026-11-16 14:05:09 Your balance is N250")!!
        assertEquals("7", m.id)
        assertTrue(m.unread)
        assertEquals("+234 803", m.number)
        assertEquals("Your balance is N250", m.text)
        assertTrue(m.time > 0)
    }

    @Test fun flowUnits() {
        assertEquals(1024L * 1024 * 1024, Router.flowBytes(1.0, "GB"))
        assertEquals(1024L * 1024, Router.flowBytes(1.0, "MB"))
    }

    @Test fun versionCompare() {
        assertTrue(Updater.newer("1.0.1", "1.0.0"))
        assertTrue(Updater.newer("1.10.0", "1.9.9"))
        assertFalse(Updater.newer("1.0.0", "1.0.0"))
        assertFalse(Updater.newer("0.9", "1.0.0"))
    }
}
