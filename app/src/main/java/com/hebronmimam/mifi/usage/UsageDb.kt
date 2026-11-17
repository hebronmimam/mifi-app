package com.hebronmimam.mifi.usage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer

/**
 * Usage history, by hour. Kept until the owner clears it; roughly 2 MB a year.
 * Times are epoch hours (UTC); the screens group them in local time.
 */
class UsageDb(ctx: Context) : SQLiteOpenHelper(ctx, "usage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE hourly(dev TEXT NOT NULL, hour INTEGER NOT NULL, box INTEGER NOT NULL DEFAULT 0, mine INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(dev, hour))")
        db.execSQL("CREATE TABLE clients(dev TEXT NOT NULL, mac TEXT NOT NULL, name TEXT, alias TEXT, mine INTEGER NOT NULL DEFAULT 0, first INTEGER, last INTEGER, PRIMARY KEY(dev, mac))")
        db.execSQL("CREATE TABLE client_hourly(dev TEXT NOT NULL, mac TEXT NOT NULL, hour INTEGER NOT NULL, online INTEGER NOT NULL DEFAULT 0, lo INTEGER NOT NULL DEFAULT 0, hi INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(dev, mac, hour))")
        db.execSQL("CREATE TABLE temps(dev TEXT NOT NULL, hour INTEGER NOT NULL, lo REAL, hi REAL, PRIMARY KEY(dev, hour))")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun setMeta(k: String, v: String?) {
        if (v == null) writableDatabase.delete("meta", "k=?", arrayOf(k))
        else writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addHour(db: SQLiteDatabase, dev: String, hour: Long, box: Long, mine: Long) {
        db.execSQL("INSERT OR IGNORE INTO hourly(dev, hour) VALUES(?, ?)", arrayOf(dev, hour))
        db.execSQL("UPDATE hourly SET box = box + ?, mine = mine + ? WHERE dev=? AND hour=?", arrayOf(box, mine, dev, hour))
    }

    fun seen(db: SQLiteDatabase, dev: String, mac: String, name: String?, now: Long) {
        db.execSQL("INSERT OR IGNORE INTO clients(dev, mac, name, first, last) VALUES(?, ?, ?, ?, ?)", arrayOf(dev, mac, name, now, now))
        db.execSQL("UPDATE clients SET last=?, name=COALESCE(?, name) WHERE dev=? AND mac=?", arrayOf(now, name, dev, mac))
    }

    fun addClientHour(db: SQLiteDatabase, dev: String, mac: String, hour: Long, onlineSec: Long, lo: Long, hi: Long) {
        db.execSQL("INSERT OR IGNORE INTO client_hourly(dev, mac, hour) VALUES(?, ?, ?)", arrayOf(dev, mac, hour))
        db.execSQL(
            "UPDATE client_hourly SET online = online + ?, lo = lo + ?, hi = hi + ? WHERE dev=? AND mac=? AND hour=?",
            arrayOf(onlineSec, lo, hi, dev, mac, hour),
        )
    }

    fun addTemp(dev: String, hour: Long, c: Double) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO temps(dev, hour, lo, hi) VALUES(?, ?, ?, ?)", arrayOf(dev, hour, c, c))
        db.execSQL("UPDATE temps SET lo = MIN(lo, ?), hi = MAX(hi, ?) WHERE dev=? AND hour=?", arrayOf(c, c, dev, hour))
    }

    // ---- reading, for the Usage screen ----

    /** Hour rows in [from, to) (epoch hours): [[hour, box, mine], …]. */
    fun hours(dev: String, from: Long, to: Long): JSONArray = JSONArray().also { out ->
        readableDatabase.rawQuery(
            "SELECT hour, box, mine FROM hourly WHERE dev=? AND hour>=? AND hour<? ORDER BY hour",
            arrayOf(dev, from.toString(), to.toString()),
        ).use { c -> while (c.moveToNext()) out.put(JSONArray().put(c.getLong(0)).put(c.getLong(1)).put(c.getLong(2))) }
    }

    /** Every device seen, with its range and online time in [from, to). */
    fun devices(dev: String, from: Long, to: Long): JSONArray = JSONArray().also { out ->
        readableDatabase.rawQuery(
            """SELECT c.mac, c.name, c.alias, c.mine, c.first, c.last,
                      COALESCE(SUM(h.lo), 0), COALESCE(SUM(h.hi), 0), COALESCE(SUM(h.online), 0)
               FROM clients c LEFT JOIN client_hourly h
                 ON h.dev = c.dev AND h.mac = c.mac AND h.hour >= ? AND h.hour < ?
               WHERE c.dev = ? GROUP BY c.mac ORDER BY 8 DESC, c.last DESC""",
            arrayOf(from.toString(), to.toString(), dev),
        ).use { c ->
            while (c.moveToNext()) out.put(
                JSONObject()
                    .put("mac", c.getString(0)).put("name", c.getString(1)).put("alias", c.getString(2))
                    .put("mine", c.getInt(3) == 1).put("first", c.getLong(4)).put("last", c.getLong(5))
                    .put("lo", c.getLong(6)).put("hi", c.getLong(7)).put("onlineSec", c.getLong(8))
            )
        }
    }

    /** One device's hours in [from, to): [[hour, online, lo, hi], …]. */
    fun clientHours(dev: String, mac: String, from: Long, to: Long): JSONArray = JSONArray().also { out ->
        readableDatabase.rawQuery(
            "SELECT hour, online, lo, hi FROM client_hourly WHERE dev=? AND mac=? AND hour>=? AND hour<? ORDER BY hour",
            arrayOf(dev, mac, from.toString(), to.toString()),
        ).use { c -> while (c.moveToNext()) out.put(JSONArray().put(c.getLong(0)).put(c.getLong(1)).put(c.getLong(2)).put(c.getLong(3))) }
    }

    fun temps(dev: String, from: Long, to: Long): JSONArray = JSONArray().also { out ->
        readableDatabase.rawQuery(
            "SELECT hour, lo, hi FROM temps WHERE dev=? AND hour>=? AND hour<? ORDER BY hour",
            arrayOf(dev, from.toString(), to.toString()),
        ).use { c -> while (c.moveToNext()) out.put(JSONArray().put(c.getLong(0)).put(c.getDouble(1)).put(c.getDouble(2))) }
    }

    fun setMine(dev: String, mac: String, mine: Boolean) {
        writableDatabase.execSQL("UPDATE clients SET mine=? WHERE dev=? AND mac=?", arrayOf(if (mine) 1 else 0, dev, mac))
    }

    fun rename(dev: String, mac: String, alias: String?) {
        writableDatabase.execSQL("UPDATE clients SET alias=? WHERE dev=? AND mac=?", arrayOf(alias?.trim()?.take(40)?.ifEmpty { null }, dev, mac))
    }

    /** The names the owner gave devices on this box, by upper-case MAC. */
    fun aliases(dev: String): Map<String, String> = runCatching {
        readableDatabase.rawQuery("SELECT UPPER(mac), alias FROM clients WHERE dev=? AND alias IS NOT NULL", arrayOf(dev)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }.getOrDefault(emptyMap())

    fun info(): JSONObject {
        val db = readableDatabase
        fun count(t: String) = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getLong(0) }
        val first = db.rawQuery("SELECT MIN(hour) FROM hourly", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        val size = runCatching { java.io.File(db.path).length() }.getOrDefault(0L)
        return JSONObject()
            .put("hours", count("hourly")).put("devices", count("clients"))
            .put("since", first?.let { it * 3_600_000L } ?: JSONObject.NULL)
            .put("bytes", size)
    }

    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("hourly", "clients", "client_hourly", "temps", "meta")) db.delete(t, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("VACUUM")
    }

    /** Everything as CSV: one section per table. */
    fun export(w: Writer) {
        val db = readableDatabase
        fun dump(title: String, sql: String) {
            w.write("# $title\n")
            db.rawQuery(sql, null).use { c ->
                w.write(c.columnNames.joinToString(",") + "\n")
                while (c.moveToNext()) {
                    w.write((0 until c.columnCount).joinToString(",") { i -> csv(c.getString(i)) } + "\n")
                }
            }
            w.write("\n")
        }
        dump("hourly totals (hour = UTC; box and mine in bytes)",
            "SELECT dev, datetime(hour*3600, 'unixepoch') AS hour_utc, box, mine FROM hourly ORDER BY dev, hour")
        dump("devices", "SELECT dev, mac, name, alias, mine, datetime(first/1000, 'unixepoch') AS first_utc, datetime(last/1000, 'unixepoch') AS last_utc FROM clients ORDER BY dev, last DESC")
        dump("device hours (least and most bytes)",
            "SELECT dev, mac, datetime(hour*3600, 'unixepoch') AS hour_utc, online AS online_sec, lo AS least, hi AS most FROM client_hourly ORDER BY dev, mac, hour")
        dump("temperature", "SELECT dev, datetime(hour*3600, 'unixepoch') AS hour_utc, lo, hi FROM temps ORDER BY dev, hour")
        w.flush()
    }

    private fun csv(s: String?): String {
        s ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
