package pl.aatrip.trip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import pl.aatrip.AppSettings
import pl.aatrip.MainActivity

/**
 * Usługa pierwszoplanowa w telefonie. Działa przy zgaszonym ekranie, a po
 * zgaszeniu silnika czeka na powrót przez czas progu wznowienia.
 */
class TripService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var reader: EngineReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedMs = 0L

    /** Co kilka sekund zamyka przeterminowaną trasę i kończy niepotrzebną usługę. */
    private val watchdog = object : Runnable {
        override fun run() {
            val settings = AppSettings(this@TripService)
            val (expired, open) = TripRepository.use(this@TripService) {
                val closed = it.expire(System.currentTimeMillis(), settings.resumeMs)
                closed to (it.current != null)
            }
            if (expired) TripRepository.save(this@TripService)

            val lastUse = maxOf(startedMs, reader?.lastEngineMs ?: 0L)
            if (!open && SystemClock.elapsedRealtime() - lastUse > settings.resumeMs) {
                stopSelf()
            } else {
                handler.postDelayed(this, 5000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            LiveState.status = "otwórz AA Trip w telefonie"
            stopSelf()
            return START_NOT_STICKY
        }
        startedMs = SystemClock.elapsedRealtime()
        if (reader == null) {
            LiveState.serviceRunning = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aatrip:recording")
                .apply { acquire(12 * 60 * 60 * 1000L) }
            reader = EngineReader(applicationContext).also { it.start() }
            handler.postDelayed(watchdog, 5000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        reader?.shutdown()
        reader = null
        wakeLock?.takeIf { it.isHeld }?.release()
        TripRepository.use(this) { it.pause() }
        TripRepository.save(this)
        LiveState.serviceRunning = false
        LiveState.status = ""
        LiveState.details = ""
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nagrywanie trasy", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AA Trip")
            .setContentText("Połączenie ze skanerem OBD")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
        true
    } catch (e: Exception) {
        // Brak uprawnienia albo Android nie pozwala na start z tła.
        Log.e("TripService", "Nie można uruchomić usługi", e)
        false
    }

    companion object {
        private const val CHANNEL = "trip"

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Uruchamia połączenie. Zwraca null albo komunikat o przeszkodzie. */
        fun connect(context: Context): String? {
            if (!hasBluetoothPermission(context)) return "Nadaj uprawnienia w telefonie"
            if (LiveState.diagnosticsRunning) return "Zakończ diagnostykę w telefonie"
            if (AppSettings(context).scannerAddress == null) return "Najpierw wybierz skaner w telefonie"
            return try {
                context.startForegroundService(Intent(context, TripService::class.java))
                null
            } catch (e: Exception) {
                Log.e("TripService", "Start zablokowany", e)
                "Android zablokował start. Otwórz AA Trip w telefonie"
            }
        }

        fun disconnect(context: Context) {
            context.stopService(Intent(context, TripService::class.java))
        }

        fun startTrip(context: Context): String? {
            if (!LiveState.serviceRunning) {
                val error = connect(context)
                if (error != null) return error
            }
            val settings = AppSettings(context)
            TripRepository.use(context) {
                it.expire(System.currentTimeMillis(), settings.resumeMs)
                it.start(System.currentTimeMillis(), settings.pricePerLiter, settings.fuelFactor)
            }
            TripRepository.save(context)
            return null
        }

        /** Zwraca true, jeśli zamknięta trasa trafiła do historii. */
        fun stopTrip(context: Context): Boolean {
            val saved = TripRepository.use(context) {
                val trip = it.current
                it.stop()
                trip != null && trip.worthKeeping
            }
            TripRepository.save(context)
            return saved
        }
    }
}
