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

    @Test fun impossibleJumpsAreMisreads() {
        // Five minutes at 4G speeds: a few GB at most. The router's whole since-boot total is not data.
        assertTrue(Attribution.plausible(3_000_000_000, 300_000))
        assertFalse(Attribution.plausible(57_700_000_000, 300_000))
        assertFalse(Attribution.plausible(1, -1))
    }

    @Test fun aCounterGoingDownIsOnlyAResetWhenTheBoxRestarted() {
        val h = 3_600_000L
        val day: (Long) -> Long = { it / (24 * h) }
        // Away 3 hours; the router has been up 1 day 14 hours, so a lower reading is a short read.
        assertFalse(Attribution.resetIsReal("wan", 38 * 3600L, 7 * h, 10 * h, day))
        // It rebooted an hour ago: a real restart.
        assertTrue(Attribution.resetIsReal("wan", 3600L, 7 * h, 10 * h, day))
        // A monthly counter resets when the day turns, never within one.
        assertTrue(Attribution.resetIsReal("month", null, 23 * h, 25 * h, day))
        assertFalse(Attribution.resetIsReal("month", null, 7 * h, 10 * h, day))
        // Nothing to go on: not a reset.
        assertFalse(Attribution.resetIsReal("session", null, 7 * h, 10 * h, day))
    }

    @Test fun awayTimeIsSpreadOverItsHours() {
        val h = 3_600_000L
        // Away from 07:30 to 10:30 with 3 GB carried: half an hour, two full hours, half an hour.
        val s = Attribution.spread(7 * h + h / 2, 10 * h + h / 2, 3_000_000_000)
        assertEquals(listOf(7L, 8L, 9L, 10L), s.map { it.first })
        assertEquals(listOf(500_000_000L, 1_000_000_000L, 1_000_000_000L, 500_000_000L), s.map { it.second })
        assertEquals(3_000_000_000L, s.sumOf { it.second })
        // Within one hour it all stays in that hour.
        assertEquals(listOf(7L to 42L), Attribution.spread(7 * h + 60_000, 7 * h + 360_000, 42))
    }

    @Test fun boxMonthIsKeptOnlyWhenItLinesUpWithTheCalendar() {
        val at = { d: Int, h: Int -> Calendar.getInstance().apply { set(2026, Calendar.DECEMBER, d, h, 30, 0) }.timeInMillis }
        assertEquals("2026-12", Attribution.monthOf(at(15, 10), null))
        assertEquals("2026-12", Attribution.monthOf(at(15, 10), 1))
        // The box may not have reset yet in the first hours of the 1st: that reading could still be November's.
        assertNull(Attribution.monthOf(at(1, 0), 1))
        assertEquals("2026-12", Attribution.monthOf(at(1, 3), 1))
        // A box whose month starts on the 15th spans two calendar months.
        assertNull(Attribution.monthOf(at(20, 10), 15))
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

    /** The U10LS shape: upper case comes from SHA256's own hex flag, LOGIN skips AD, AD uses paswordAlgorithmsCookie. */
    @Test fun schemeFromBundledSha256HexFlag() {
        val js = """
            WEB_ATTR_IF_SUPPORT_SHA256:2,ACCESSIBLE_ID_SUPPORT:!0
            function paswordAlgorithmsCookie(f){return SHA256(f)}function SHA256(q){var y=1,g=1;return function(l){for(var i=g?"0123456789ABCDEF":"0123456789abcdef",r="";;){}}(q)}
            function cF(g,b,f,m,d){if(cL.ACCESSIBLE_ID_SUPPORT&&d&&"LOGIN"!=g.goformId){var j=paswordAlgorithmsCookie(rd0+rd1),l=bI({nv:"RD"}).RD,h=paswordAlgorithmsCookie(j+l);g.AD=h}}
            function b(f){var d=bI({nv:"LD"}).LD;return{isTest:aQ,goformId:"LOGIN",password:"2"==cL.WEB_ATTR_IF_SUPPORT_SHA256?paswordAlgorithmsCookie(paswordAlgorithmsCookie(f.password)+d):x}}
        """.trimIndent()
        val s = MifiScheme.analyze(js)
        assertEquals(2, s.login)
        assertTrue(s.upper)
        assertEquals("sha256", s.ad)
        assertFalse(s.loginAd)
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
