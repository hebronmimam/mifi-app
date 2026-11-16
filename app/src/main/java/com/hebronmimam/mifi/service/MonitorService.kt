package com.hebronmimam.mifi.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.hebronmimam.mifi.MiFiApp

/**
 * Keeps the battery number in the status bar and checks the box in the
 * background: every few minutes, every minute when the battery is low, every
 * fifteen in battery saver. Each check is woken by an alarm, so it survives Doze.
 */
class MonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val n = Monitor.statusNotification(this, (application as MiFiApp).devices.last)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifier.ID_STATUS, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as MiFiApp
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mifi:check")
        wl.setReferenceCounted(false)
        wl.acquire(60_000)
        app.io.execute {
            var next = app.prefs.checkMinutes * 60_000L
            try {
                next = app.monitor.check()
            } catch (_: Throwable) {
            } finally {
                schedule(this, next)
                if (wl.isHeld) wl.release()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHECK = "com.hebronmimam.mifi.CHECK"
        const val ACTION_STOP = "com.hebronmimam.mifi.STOP"

        @Volatile var running = false
            private set

        fun wanted(ctx: Context): Boolean {
            val p = (ctx.applicationContext as MiFiApp).prefs
            return p.statusBar || p.smsNotify || p.lowAlert || p.recordUsage
        }

        /** Start (or nudge) the service. Safe to call from the foreground or from boot. */
        fun start(ctx: Context) {
            if (!wanted(ctx)) { stop(ctx); return }
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_CHECK)
            runCatching {
                if (running || Build.VERSION.SDK_INT < 26) ctx.startService(i) else ctx.startForegroundService(i)
            }
        }

        fun stop(ctx: Context) {
            cancel(ctx)
            if (running) runCatching { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)) }
        }

        fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, alarm(ctx))
        }

        private fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))

        private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, CheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** The alarm that wakes each background check. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = MonitorService.start(context)
}

/** Back in the status bar after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> MonitorService.start(context)
        }
    }
}
