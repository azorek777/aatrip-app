package pl.aatrip.trip

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import pl.aatrip.AppSettings
import pl.aatrip.ble.ElmBleClient
import pl.aatrip.ble.connectAndOpen
import pl.aatrip.ble.findScanner
import pl.aatrip.obd.Obd

/**
 * Wątek usługi: utrzymuje połączenie ze skanerem, czyta silnik i przekazuje
 * odczyty do nagrywania. Wysyła tylko polecenia AT i zapytania trybu 01.
 */
class EngineReader(private val context: Context) : Thread("EngineReader") {

    @Volatile private var stopped = false

    /** Kiedy ostatnio silnik pracował (zegar monotoniczny). */
    @Volatile var lastEngineMs = 0L
        private set

    private val settings = AppSettings(context)

    /** Skaner znaleziony skanowaniem, gdy sam adres nie wystarczył. */
    private var found: BluetoothDevice? = null

    fun shutdown() {
        stopped = true
        interrupt()
    }

    override fun run() {
        while (!stopped) {
            try {
                session()
                if (!stopped) sleep(RETRY_MS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (stopped) break
                report("błąd połączenia", e.toString())
                pauseTrip()
                try {
                    sleep(RETRY_MS)
                } catch (stop: InterruptedException) {
                    break
                }
            }
        }
    }

    /** Jedno połączenie: od nawiązania do utraty. */
    private fun session() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val address = settings.scannerAddress
        if (adapter == null || !adapter.isEnabled) {
            report("włącz Bluetooth")
            return
        }
        if (address == null) {
            report("wybierz skaner w telefonie")
            return
        }
        report("łączenie")
        val device = found?.takeIf { it.address == address } ?: adapter.getRemoteDevice(address)
        val client = ElmBleClient(context, device) {}
        try {
            if (!client.connectAndOpen()) {
                report("brak skanera")
                client.close()
                found = adapter.bluetoothLeScanner?.let { findScanner(it, address, settings.scannerName) }
                return
            }
            // ATSP6: CAN 11 bit, 500 kb/s, tak zgłosiła się ta Octavia.
            for (command in listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP6")) {
                client.send(command, 5000)
            }
            read(client)
        } finally {
            client.close()
            if (!stopped) pauseTrip()
        }
    }

    private fun read(client: ElmBleClient) {
        var maxRatio = -1
        var silent = 0
        var lastRunning = SystemClock.elapsedRealtime()
        var lastSaved = 0L
        var engineWasOff = false

        while (!stopped && client.isConnected) {
            val answer = client.send(Obd.command(0x0C), 3000)
            if (answer == null) {
                // Adapter milczy: po kilku próbach łączymy się od nowa.
                if (++silent >= MAX_SILENT) return
                continue
            }
            silent = 0

            val rpm = word(answer, 0x0C)?.let { it / 4 } ?: 0
            if (rpm == 0) {
                val offMs = SystemClock.elapsedRealtime() - lastRunning
                if (offMs >= ENGINE_OFF_MS || engineWasOff) {
                    engineWasOff = true
                    report("silnik wyłączony")
                    pauseTrip()
                }
                // Po minucie postoju pytamy rzadziej.
                sleep(if (offMs > 60_000) 5000L else 1000L)
                continue
            }
            engineWasOff = false
            lastRunning = SystemClock.elapsedRealtime()
            lastEngineMs = lastRunning

            // Bez skali lambdy wynik byłby kilkanaście razy zawyżony, więc czekamy na nią.
            if (maxRatio < 0) {
                maxRatio = bytes(client.send(Obd.command(0x4F)), 0x4F)?.getOrNull(0) ?: -1
                if (maxRatio < 0) continue
            }

            val speed = bytes(client.send(Obd.command(0x0D)), 0x0D)?.getOrNull(0) ?: continue
            val maf = word(client.send(Obd.command(0x10)), 0x10)?.let { it / 100.0 } ?: continue
            val lambdaBytes = bytes(client.send(Obd.command(0x24)), 0x24)
            val rate = lambdaBytes?.let { Obd.fuelRate(maf, it, maxRatio) }
            val lambda = lambdaBytes?.let { Obd.lambda(it, maxRatio) }

            val sample = Sample(
                wallMs = System.currentTimeMillis(),
                monoMs = SystemClock.elapsedRealtime(),
                speedKmh = speed,
                mafGramsPerSecond = maf,
                litersPerHour = rate,
            )
            var recording = false
            val closed = TripRepository.use(context) {
                if (stopped) return@use false
                val result = it.sample(sample, settings.resumeMs)
                recording = it.current != null
                result
            }

            report(
                if (recording) "nagrywanie" else "gotowe",
                "obroty $rpm obr/min\nprędkość $speed km/h\n" +
                    "MAF ${Obd.num(maf)} g/s\n" +
                    "lambda ${lambda?.let { Obd.num(it) } ?: "poza skalą"}\n" +
                    "spalanie bez korekty " + (rate?.let { "${Obd.num(it)} l/h" } ?: "sonda jeszcze nie mierzy"),
            )

            if (closed || sample.monoMs - lastSaved >= SAVE_EVERY_MS) {
                TripRepository.save(context)
                lastSaved = sample.monoMs
            }
        }
    }

    /** Po zatrzymaniu wątku nie nadpisujemy już stanu. */
    private fun report(status: String, details: String = "") {
        if (stopped) return
        LiveState.status = status
        LiveState.details = details
    }

    private fun pauseTrip() {
        val changed = TripRepository.use(context) {
            val running = it.current?.paused == false
            if (running) it.pause()
            running
        }
        if (changed) TripRepository.save(context)
    }

    private fun bytes(response: String?, pid: Int): IntArray? =
        response?.let { Obd.payloads(it, pid).firstOrNull() }

    /** Dwubajtowa wartość A*256+B. */
    private fun word(response: String?, pid: Int): Int? =
        bytes(response, pid)?.takeIf { it.size >= 2 }?.let { it[0] * 256 + it[1] }

    private companion object {
        const val RETRY_MS = 5000L
        const val ENGINE_OFF_MS = 8000L
        const val SAVE_EVERY_MS = 5000L
        const val MAX_SILENT = 5
    }
}
