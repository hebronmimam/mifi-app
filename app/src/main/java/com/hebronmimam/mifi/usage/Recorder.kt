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
        val phone = phoneCounter()
        val prevAt = db.meta("t:$dev")?.toLongOrNull()
        val prevCounter = db.meta("c:$dev")?.toLongOrNull()
        val prevPhone = db.meta("p:$dev")?.toLongOrNull()
        val hour = now / 3_600_000L

        val b = Attribution.delta(prevCounter, counter)
        val p = Attribution.delta(prevPhone, phone) ?: 0
        val gap = if (prevAt == null) Long.MAX_VALUE else now - prevAt
        val near = gap in 0..Attribution.MAX_GAP_MS

        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (b != null) {
                // The box's own total is exact whatever the gap; the phone's share only when it was here.
                val mine = if (near) p.coerceAtMost(b) else 0
                db.addHour(w, dev, hour, b, mine)
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
            if (counter != null) db.setMeta("c:$dev", counter.toString())
            if (phone != null) db.setMeta("p:$dev", phone.toString())
            db.setMeta("t:$dev", now.toString())
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        s.tempC?.let { if (it > -40 && it < 150) db.addTemp(dev, hour, it) }
    }
}
