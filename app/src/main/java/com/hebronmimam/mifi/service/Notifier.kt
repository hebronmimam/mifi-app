package com.hebronmimam.mifi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import com.hebronmimam.mifi.MainActivity
import com.hebronmimam.mifi.R
import com.hebronmimam.mifi.device.Message

object Notifier {
    const val CH_STATUS = "status"
    const val CH_SMS = "sms"
    const val CH_ALERT = "alert"
    const val CH_UPDATE = "update"

    const val ID_STATUS = 1
    const val ID_LOW = 2
    const val ID_UPDATE = 3
    const val ID_HOT = 4
    private const val ID_SMS_SUMMARY = 5
    private const val ID_SMS_BASE = 1000
    private const val GROUP_SMS = "sms"
    private const val GROUP_STATUS = "status"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.ch_status_desc); setShowBadge(false)
            },
            NotificationChannel(CH_SMS, ctx.getString(R.string.ch_sms), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_sms_desc)
            },
            NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_alert_desc)
            },
            NotificationChannel(CH_UPDATE, ctx.getString(R.string.ch_update), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ctx.getString(R.string.ch_update_desc)
            },
        ))
    }

    fun open(ctx: Context, route: String?, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) i.putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun builder(ctx: Context, ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch) else @Suppress("DEPRECATION") Notification.Builder(ctx)

    /** The always-on notification whose small icon is the battery number. */
    fun status(ctx: Context, percent: Int?, title: String, text: String): Notification {
        val b = builder(ctx, CH_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_STATUS) // its own group, so Android never bundles the texts under it
            .setContentIntent(open(ctx, null, 0))
            .setColor(0xFFBCF500.toInt())
        if (Build.VERSION.SDK_INT >= 23 && percent != null) b.setSmallIcon(Icon.createWithBitmap(numberIcon(percent)))
        else b.setSmallIcon(R.drawable.ic_stat_mifi)
        if (Build.VERSION.SDK_INT < 26) @Suppress("DEPRECATION") b.setPriority(Notification.PRIORITY_LOW)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /** The percentage drawn as the status-bar icon; a dash when unknown. */
    fun numberIcon(percent: Int?): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (percent == 100) 44f else 56f
        }
        val s = percent?.toString() ?: "–"
        val y = 32f - (p.descent() + p.ascent()) / 2f
        c.drawText(s, 32f, y, p)
        return bmp
    }

    fun low(ctx: Context, percent: Int) {
        val n = builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_mifi)
            .setContentTitle("MiFi battery at $percent%")
            .setContentText("Plug it in soon.")
            .setAutoCancel(true)
            .setContentIntent(open(ctx, null, ID_LOW))
            .build()
        nm(ctx).notify(ID_LOW, n)
    }

    fun cancelLow(ctx: Context) = nm(ctx).cancel(ID_LOW)

    fun hot(ctx: Context, celsius: Int) {
        val n = builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_mifi)
            .setContentTitle("Router at $celsius °C")
            .setContentText("Above 70 °C is too hot. Clear its top vents.")
            .setAutoCancel(true)
            .setContentIntent(open(ctx, null, ID_HOT))
            .build()
        nm(ctx).notify(ID_HOT, n)
    }

    /**
     * Texts go in their own group with a summary. Without one, Android bundles them under the
     * always-on battery notification, which hides them in the silent section once the alert is gone.
     */
    fun sms(ctx: Context, deviceName: String, m: Message) {
        val id = smsId(m.id)
        val n = builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(m.number.ifBlank { deviceName })
            .setContentText(m.text)
            .setStyle(Notification.BigTextStyle().bigText(m.text))
            .setSubText(deviceName)
            .setWhen(if (m.time > 0) m.time else System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setColor(0xFFBCF500.toInt())
            .setGroup(GROUP_SMS)
            .setContentIntent(open(ctx, "msg:${m.id}", id))
            .build()
        nm(ctx).notify(id, n)
        smsSummary(ctx, deviceName)
    }

    private fun smsId(msgId: String) = ID_SMS_BASE + (msgId.hashCode() and 0x7fff)

    /** The group's summary, or nothing once the last text is gone. */
    private fun smsSummary(ctx: Context, deviceName: String?) {
        if (Build.VERSION.SDK_INT < 24) return
        val shown = nm(ctx).activeNotifications.filter { it.id >= ID_SMS_BASE && it.notification.group == GROUP_SMS }
        if (shown.isEmpty()) { nm(ctx).cancel(ID_SMS_SUMMARY); return }
        val style = Notification.InboxStyle()
        shown.sortedByDescending { it.notification.`when` }.take(5).forEach {
            val x = it.notification.extras
            style.addLine("${x.getCharSequence(Notification.EXTRA_TITLE) ?: ""}  ${x.getCharSequence(Notification.EXTRA_TEXT) ?: ""}")
        }
        val count = if (shown.size == 1) "1 new text" else "${shown.size} new texts"
        val n = builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle(count)
            .setStyle(style.setSummaryText(deviceName))
            .setColor(0xFFBCF500.toInt())
            .setGroup(GROUP_SMS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(open(ctx, null, ID_SMS_SUMMARY))
        if (Build.VERSION.SDK_INT >= 26) n.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        nm(ctx).notify(ID_SMS_SUMMARY, n.build())
    }

    fun clearSms(ctx: Context, ids: List<String>) {
        ids.forEach { nm(ctx).cancel(smsId(it)) }
        smsSummary(ctx, null)
    }

    fun update(ctx: Context, version: String) {
        val n = builder(ctx, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("MiFi $version is ready")
            .setContentText("Open the app and tap Install.")
            .setAutoCancel(true)
            .setContentIntent(open(ctx, "settings:app", ID_UPDATE))
            .build()
        nm(ctx).notify(ID_UPDATE, n)
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
}
