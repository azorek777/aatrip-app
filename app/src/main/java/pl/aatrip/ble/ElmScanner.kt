package pl.aatrip.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Szuka znanego już skanera po adresie. Potrzebne, gdy Android go "zapomniał"
 * (np. po restarcie telefonu) i bezpośrednie połączenie się nie udaje.
 * Blokuje do 8 s. Filtr pozwala skanować także przy zgaszonym ekranie.
 */
@SuppressLint("MissingPermission")
fun findScanner(scanner: BluetoothLeScanner, address: String, name: String?): BluetoothDevice? {
    val done = CountDownLatch(1)
    var found: BluetoothDevice? = null
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address != address) return
            found = result.device
            done.countDown()
        }

        override fun onScanFailed(errorCode: Int) {
            done.countDown()
        }
    }
    val filters = mutableListOf(ScanFilter.Builder().setDeviceAddress(address).build())
    if (name != null) filters.add(ScanFilter.Builder().setDeviceName(name).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    scanner.startScan(filters, settings, callback)
    try {
        done.await(8, TimeUnit.SECONDS)
    } finally {
        scanner.stopScan(callback)
    }
    return found
}
