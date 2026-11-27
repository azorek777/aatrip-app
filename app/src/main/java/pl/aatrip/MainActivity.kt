package pl.aatrip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import pl.aatrip.trip.Format
import pl.aatrip.trip.LiveState
import pl.aatrip.trip.TripRepository
import pl.aatrip.trip.TripService

/** Ekran główny w telefonie: połączenie ze skanerem i podgląd. Nagrywa się z auta. */
@SuppressLint("MissingPermission") // uprawnienia sprawdzamy w withPermissions()
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings

    private lateinit var scanner: TextView
    private lateinit var state: TextView
    /** Komunikaty o szukaniu skanera. */
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var trip: TextView
    private lateinit var live: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var scanning = false
    private val found = HashSet<String>()

    /** Co zrobić po przyznaniu uprawnień. */
    private var afterPermissions: (() -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val address = result.device.address
            if (!found.add(address)) return
            val entry = button(devices, "$name\n$address") { choose(address, name) }
            // Prawdopodobne skanery OBD na górę listy.
            val likely = listOf("vlink", "icar", "obd", "vgate").any { name.contains(it, ignoreCase = true) }
            if (likely) {
                devices.removeView(entry)
                devices.addView(entry, 0)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(stopScan)
            scanning = false
            status.text = "Skanowanie nieudane (kod $errorCode). Wyłącz i włącz Bluetooth."
        }
    }

    private val stopScan = Runnable {
        stopSearch()
        status.text = if (found.isEmpty()) {
            "Nic nie znaleziono. Czy skaner jest w gnieździe, a inne aplikacje OBD zamknięte?"
        } else {
            "Wybierz skaner z listy."
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            showState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        val root = column()

        label(root, "AA Trip", 26f)
        scanner = label(root)
        state = label(root, size = 18f)
        connectButton = button(root, "Connect") { withPermissions { connect() } }
        disconnectButton = button(root, "Rozłącz") { TripService.disconnect(this) }
        button(root, "Wybierz inny skaner") { withPermissions { startSearch() } }
        status = label(root)
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devices)
        trip = label(root, size = 18f)
        live = label(root)
        button(root, "Historia tras") { open(HistoryActivity::class.java) }
        button(root, "Ustawienia") { open(SettingsActivity::class.java) }
        button(root, "Diagnostyka") { open(DiagnosticActivity::class.java) }
        button(root, "Uprawnienia i bateria") { openAppSettings() }
        label(root, installReport(), 12f)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        if (scanning) {
            stopSearch()
            status.text = ""
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = afterPermissions
        afterPermissions = null
        if (hasAll(bluetoothPermissions())) {
            action?.invoke()
        } else {
            toast("Bez uprawnień Bluetooth nie połączę się ze skanerem. Nadaj je w „Uprawnienia i bateria”.")
        }
    }

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasAll(permissions: List<String>) =
        permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun withPermissions(action: () -> Unit) {
        val wanted = bluetoothPermissions().toMutableList()
        // Powiadomienie o działającej usłudze. Odmowa niczego nie blokuje.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (hasAll(wanted)) {
            action()
            return
        }
        afterPermissions = action
        requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun connect() {
        if (settings.scannerAddress == null) {
            startSearch()
            return
        }
        stopSearch()
        devices.removeAllViews()
        status.text = ""
        TripService.connect(this)?.let { toast(it) }
        showState()
    }

    private fun startSearch() {
        if (scanning) return
        if (LiveState.serviceRunning || LiveState.diagnosticsRunning) {
            toast("Najpierw się rozłącz.")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val bluetooth = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (bluetooth == null) {
            status.text = "Włącz Bluetooth i spróbuj ponownie."
            return
        }
        found.clear()
        devices.removeAllViews()
        scanning = true
        status.text = "Szukam urządzeń (10 s)..."
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetooth.startScan(null, scanSettings, scanCallback)
        handler.postDelayed(stopScan, 10_000)
    }

    private fun stopSearch() {
        handler.removeCallbacks(stopScan)
        if (!scanning) return
        scanning = false
        getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun choose(address: String, name: String) {
        settings.saveScanner(address, name)
        stopSearch()
        connect()
    }

    private fun showState() {
        val name = settings.scannerName
        scanner.text = if (name == null) "Skaner: nie wybrano" else "Skaner: $name"
        connectButton.isEnabled = !LiveState.serviceRunning
        disconnectButton.isEnabled = LiveState.serviceRunning
        state.text = if (LiveState.serviceRunning) {
            "Stan: ${LiveState.status}"
        } else {
            "Rozłączono. Nagrywanie włączysz przyciskiem Start na ekranie w aucie."
        }
        live.text = LiveState.details
        trip.text = currentTrip()
    }

    private fun currentTrip(): String {
        var expired = false
        val text = TripRepository.use(this) { recorder ->
            expired = recorder.expire(System.currentTimeMillis(), settings.resumeMs)
            val open = recorder.current ?: return@use ""
            val state = if (open.paused) "Trasa wstrzymana" else "Trwa nagrywanie"
            "$state\n${Format.distance(open.distanceKm)}, średnio ${Format.average(open.averagePer100)}\n" +
                "${Format.fuelAndCost(open)}\n" +
                "w tym szacunek bez sondy: ${Format.number(open.estimatedShare * 100, 0)}%"
        }
        if (expired) TripRepository.save(this)
        return text
    }

    private fun open(screen: Class<out Activity>) = startActivity(Intent(this, screen))

    private fun openAppSettings() {
        toast("Dla pewnego działania z autem ustaw Bateria: bez ograniczeń.")
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun installReport(): String {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
        return "Wersja $version\ninstaller: ${installer ?: "brak"}"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
