package com.hebronmimam.mifi.usage

import android.net.TrafficStats
import com.hebronmimam.mifi.device.Status

/**
 * The per-device arithmetic, kept free of Android so it can be tested.
 *
 * Neither box counts bytes per device. At each check we know what the whole box
 * carried since the last check (B), what this phone carried on Wi-Fi (P, exact),
 * and which other devices were online. For each of those devices:
 *   most  += B - P                 (everything it could have used)
 *   least += B - P  only if it was the only other device online
 */
object Attribution {
    data class Share(val mac: String, val lo: Long, val hi: Long)

    /** Bytes since the last reading of a counter that may reset (monthly roll-over, reconnect). */
    fun delta(prev: Long?, now: Long?): Long? {
        if (prev == null || now == null || now < 0) return null
        return if (now >= prev) now - prev else now
    }

    fun split(box: Long, phone: Long, others: List<String>): List<Share> {
        val rest = (box - phone).coerceAtLeast(0)
        return others.distinct().let { o -> o.map { Share(it, if (o.size == 1) rest else 0, rest) } }
    }

    /** Checks further apart than this mean the phone was away; nothing is attributed. */
    const val MAX_GAP_MS = 20 * 60_000L

    /** Faster than any 4G MiFi or home router carries (320 Mbps). A bigger jump is a misread, not data. */
    const val MAX_BYTES_PER_SEC = 40_000_000L

    fun plausible(bytes: Long, overMs: Long): Boolean = overMs > 0 && bytes <= (overMs / 1000 + 60) * MAX_BYTES_PER_SEC

    /**
     * A counter that went down only restarted if the box says so: it (or its line) came up within the
     * span, or, for a monthly counter, the day changed. Anything else is a short read.
     */
    fun resetIsReal(kind: String?, upSec: Long?, fromMs: Long, toMs: Long, dayOf: (Long) -> Long): Boolean = when {
        kind == "month" -> dayOf(fromMs) != dayOf(toMs)
        upSec != null -> upSec * 1000 <= (toMs - fromMs) + 60_000
        else -> false
    }

    private const val HOUR_MS = 3_600_000L

    /**
     * What the box carried between two readings, split over the hours in between by time.
     * The phone being away for the night must not land the whole night in the hour it came back.
     */
    fun spread(fromMs: Long, toMs: Long, bytes: Long): List<Pair<Long, Long>> {
        if (toMs <= fromMs) return listOf(toMs / HOUR_MS to bytes)
        val out = mutableListOf<Pair<Long, Long>>()
        var t = fromMs
        var left = bytes
        while (t < toMs) {
            val hour = t / HOUR_MS
            val end = minOf((hour + 1) * HOUR_MS, toMs)
            val part = if (end >= toMs) left else bytes * (end - t) / (toMs - fromMs)
            if (part > 0) out += hour to part
            left -= part
            t = end
        }
        return out
    }
}

/** Writes each check into [UsageDb]. */
class Recorder(private val db: UsageDb) {

    /** This phone's bytes over Wi-Fi (all interfaces minus mobile). */
    fun phoneCounter(): Long? {
        val total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        val mobile = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        if (TrafficStats.getTotalRxBytes() == TrafficStats.UNSUPPORTED.toLong()) return null
        return (total - mobile.coerceAtLeast(0)).coerceAtLeast(0)
    }

    @Synchronized
    fun record(dev: String, s: Status, myIp: String?, now: Long = System.currentTimeMillis()) {
        val counter = s.counter
        val kind = s.counterKind
        val phone = phoneCounter()
        val prevAt = db.meta("t:$dev")?.toLongOrNull()
        val prevCounter = db.meta("c:$dev")?.toLongOrNull()
        val prevCountedAt = db.meta("ct:$dev")?.toLongOrNull()
        val prevKind = db.meta("k:$dev")
        val prevPhone = db.meta("p:$dev")?.toLongOrNull()
        val hour = now / 3_600_000L

        // A router reads its total from one of several counters. When a reading falls back to another one,
        // the two can't be compared: the difference would be the gap between counters, not data.
        val span = if (prevCountedAt == null) -1 else now - prevCountedAt
        val went = counter != null && prevCounter != null && counter < prevCounter
        val up = listOfNotNull(s.uptimeSec, s.sessionSec).minOrNull()
        val resetOk = !went || (prevCountedAt != null &&
            Attribution.resetIsReal(kind, up, prevCountedAt, now) { java.util.Calendar.getInstance().run { timeInMillis = it; get(java.util.Calendar.YEAR) * 400L + get(java.util.Calendar.DAY_OF_YEAR) } })
        val b = Attribution.delta(prevCounter, counter)
            ?.takeIf { prevKind != null && prevKind == kind && resetOk && Attribution.plausible(it, span) }
        val p = Attribution.delta(prevPhone, phone) ?: 0
        val gap = if (prevAt == null) Long.MAX_VALUE else now - prevAt
        val near = gap in 0..Attribution.MAX_GAP_MS

        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (b != null) {
                // The box's own total is exact whatever the gap; the phone's share only when it was here.
                if (near && span <= Attribution.MAX_GAP_MS) db.addHour(w, dev, hour, b, p.coerceAtMost(b))
                else Attribution.spread(prevCountedAt!!, now, b).forEach { (h, x) -> db.addHour(w, dev, h, x, 0) }
            }
            val clients = s.clients
            if (clients != null) {
                val me = clients.firstOrNull { myIp != null && it.ip == myIp }
                if (me != null) db.setMeta("me:$dev", me.mac)
                val others = clients.filter { it !== me }
                others.forEach { db.seen(w, dev, it.mac, it.name, now) }
                if (near && b != null) {
                    val sec = gap / 1000
                    for (share in Attribution.split(b, p, others.map { it.mac })) {
                        db.addClientHour(w, dev, share.mac, hour, sec, share.lo, share.hi)
                    }
                }
            }
            if (counter != null) {
                db.setMeta("c:$dev", counter.toString())
                db.setMeta("ct:$dev", now.toString())
                db.setMeta("k:$dev", kind ?: "?")
            }
            if (phone != null) db.setMeta("p:$dev", phone.toString())
            db.setMeta("t:$dev", now.toString())
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        s.tempC?.let { if (it > -40 && it < 150) db.addTemp(dev, hour, it) }
    }
}
