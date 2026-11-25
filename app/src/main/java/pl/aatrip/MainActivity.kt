package pl.aatrip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import pl.aatrip.obd.DiagnosticLog
import pl.aatrip.obd.DiagnosticSession

/** Ekran w telefonie. Etap 2: połączenie ze skanerem i diagnostyka. */
@SuppressLint("MissingPermission") // uprawnienia sprawdzamy w startSearch()
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val log = DiagnosticLog()
    private var session: DiagnosticSession? = null
    private var scanning = false

    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var live: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private val found = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!found.add(result.device.address)) return
            val entry = button("$name\n${result.device.address}") { connect(result.device, name) }
            // Prawdopodobne skanery OBD na górę listy.
            val likely = listOf("vlink", "icar", "obd", "vgate").any { name.contains(it, ignoreCase = true) }
            if (likely) devices.addView(entry, 0) else devices.addView(entry)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status.text = "Skanowanie nieudane (kod $errorCode)."
        }
    }

    private val stopScan = Runnable {
        stopSearch()
        status.text = if (found.isEmpty()) {
            "Nic nie znaleziono. Czy skaner jest w gnieździe, a Car Scanner zamknięty?"
        } else {
            "Wybierz skaner z listy."
        }
    }

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

        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 3, padding, padding * 3)
        }

        connectButton = button("Connect (szukaj skanera)") { startSearch() }
        disconnectButton = button("Rozłącz") { session?.stop() }
        disconnectButton.isEnabled = false
        status = label(18f).apply { text = "Włóż skaner, włącz zapłon i naciśnij Connect." }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        live = label(18f)
        logView = label(11f).apply { typeface = Typeface.MONOSPACE }

        root.addView(label(14f).apply { text = installReport() })
        root.addView(connectButton)
        root.addView(disconnectButton)
        root.addView(button("Udostępnij log") { shareLog() })
        root.addView(button("Kopiuj log") { copyLog() })
        root.addView(status)
        root.addView(devices)
        root.addView(live)
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        stopSearch()
        session?.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startSearch()
        } else {
            status.text = "Bez uprawnień Bluetooth nie znajdę skanera. Nadaj je w ustawieniach aplikacji."
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun startSearch() {
        if (session != null || scanning) return
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            status.text = "Włącz Bluetooth i spróbuj ponownie."
            return
        }
        found.clear()
        devices.removeAllViews()
        scanning = true
        status.text = "Szukam urządzeń (10 s)..."
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(stopScan, 10_000)
    }

    private fun stopSearch() {
        handler.removeCallbacks(stopScan)
        if (!scanning) return
        scanning = false
        getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connect(device: BluetoothDevice, name: String) {
        if (session != null) return
        stopSearch()
        devices.removeAllViews()
        live.text = ""
        log.clear()
        log.add("AA Trip ${appVersion()}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
        log.add("Urządzenie: $name")
        connectButton.isEnabled = false
        disconnectButton.isEnabled = true

        session = DiagnosticSession(
            context = applicationContext,
            device = device,
            log = log,
            onStatus = { message -> runOnUiThread { status.text = message } },
            onLive = { values -> runOnUiThread { live.text = values } },
            onFinished = {
                runOnUiThread {
                    session = null
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    logView.text = log.tail(40)
                }
            },
        ).also { it.start() }
        handler.post(refreshLog)
    }

    private fun shareLog() {
        if (log.isEmpty()) {
            toast("Log jest pusty. Najpierw połącz się ze skanerem.")
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

    private fun installReport(): String {
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = packageManager.getInstallSourceInfo(packageName)
            "installer: ${info.installingPackageName ?: "brak"}\n" +
                "initiator: ${info.initiatingPackageName ?: "brak"}"
        } else {
            @Suppress("DEPRECATION")
            val installer = packageManager.getInstallerPackageName(packageName)
            "installer: ${installer ?: "brak"}"
        }
        return "AA Trip ${appVersion()}\n$source"
    }

    private fun appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun label(size: Float) = TextView(this).apply {
        textSize = size
        setPadding(0, 16, 0, 16)
        setTextIsSelectable(true)
    }

    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }
}
