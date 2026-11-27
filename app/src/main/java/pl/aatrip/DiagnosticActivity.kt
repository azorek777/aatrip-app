package pl.aatrip

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import pl.aatrip.obd.DiagnosticLog
import pl.aatrip.obd.DiagnosticSession
import pl.aatrip.trip.LiveState
import pl.aatrip.trip.TripService

/** Pełny odczyt parametrów z logiem do udostępnienia. */
@SuppressLint("MissingPermission") // uprawnienie sprawdzamy w start()
class DiagnosticActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val log = DiagnosticLog()
    private var session: DiagnosticSession? = null

    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var logView: TextView

    /** Odświeża podgląd logu raz na sekundę, gdy trwa sesja. */
    private val refreshLog = object : Runnable {
        override fun run() {
            logView.text = log.tail(40)
            if (session != null) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = column()

        label(root, "Diagnostyka", 26f)
        button(root, "Start diagnostyki") { start() }
        button(root, "Zakończ") { session?.stop() }
        button(root, "Udostępnij log") { shareLog() }
        button(root, "Kopiuj log") { copyLog() }
        status = label(root, "Włącz zapłon i naciśnij Start diagnostyki.", 18f)
        live = label(root, size = 18f)
        logView = label(root, size = 11f).apply { typeface = Typeface.MONOSPACE }
    }

    override fun onDestroy() {
        session?.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun start() {
        if (session != null) return
        val settings = AppSettings(this)
        val address = settings.scannerAddress
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val problem = when {
            LiveState.serviceRunning -> "Najpierw naciśnij Rozłącz na ekranie głównym."
            LiveState.diagnosticsRunning -> "Poprzednia diagnostyka jeszcze się kończy."
            address == null -> "Najpierw wybierz skaner na ekranie głównym."
            !TripService.hasBluetoothPermission(this) -> "Brak uprawnienia Bluetooth."
            adapter == null || !adapter.isEnabled -> "Włącz Bluetooth."
            else -> null
        }
        if (problem != null || address == null || adapter == null) {
            status.text = problem.orEmpty()
            return
        }

        live.text = ""
        log.clear()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        log.add("AA Trip $version, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
        log.add("Urządzenie: ${settings.scannerName}")
        LiveState.diagnosticsRunning = true

        session = DiagnosticSession(
            context = applicationContext,
            device = adapter.getRemoteDevice(address),
            log = log,
            onStatus = { message -> runOnUiThread { status.text = message } },
            onLive = { values -> runOnUiThread { live.text = values } },
            onFinished = {
                LiveState.diagnosticsRunning = false
                runOnUiThread {
                    session = null
                    logView.text = log.tail(40)
                }
            },
        ).also { it.start() }
        handler.post(refreshLog)
    }

    private fun shareLog() {
        if (log.isEmpty()) {
            toast("Log jest pusty. Najpierw uruchom diagnostykę.")
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "AA Trip: log diagnostyczny")
            .putExtra(Intent.EXTRA_TEXT, log.text())
        startActivity(Intent.createChooser(intent, "Udostępnij log"))
    }

    private fun copyLog() {
        if (log.isEmpty()) {
            toast("Log jest pusty.")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("AA Trip log", log.text()))
        toast("Skopiowano log.")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
