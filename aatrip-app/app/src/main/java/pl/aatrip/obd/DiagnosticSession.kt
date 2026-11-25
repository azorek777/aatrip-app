package pl.aatrip.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import pl.aatrip.ble.ElmBleClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostyka etapu 2: łączy się ze skanerem, sprawdza obsługiwane parametry
 * i odczytuje je w pętli. Wysyła wyłącznie polecenia AT (ustawienia adaptera)
 * i zapytania trybu 01 (odczyt bieżących danych). Niczego w aucie nie zapisuje.
 */
class DiagnosticSession(
    context: Context,
    device: BluetoothDevice,
    private val log: DiagnosticLog,
    private val onStatus: (String) -> Unit,
    private val onLive: (String) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val client = ElmBleClient(context, device) { log.add(it) }

    @Volatile private var stopped = false

    private val thread = Thread {
        try {
            run()
        } catch (e: InterruptedException) {
            onStatus("Rozłączono.") // zatrzymanie przez użytkownika
        } catch (e: Exception) {
            log.add("BŁĄD: $e")
            onStatus("Błąd: ${e.message}")
        } finally {
            client.close()
            onFinished()
        }
    }

    fun start() = thread.start()

    fun stop() {
        stopped = true
        thread.interrupt()
    }

    private fun run() {
        onStatus("Łączenie ze skanerem...")
        val channels = client.connect()
        if (channels.isEmpty()) {
            fail("Nie udało się połączyć. Zamknij Car Scanner i spróbuj ponownie.")
            return
        }
        if (!openWorkingChannel(channels)) {
            fail("Połączono, ale adapter nie odpowiada na polecenia.")
            return
        }

        onStatus("Konfiguracja adaptera...")
        for (command in listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP0", "ATI", "AT@1", "ATRV")) {
            ask(command, 5000)
        }

        onStatus("Szukanie sterownika silnika...")
        val supported = readSupportedPids()
        ask("ATDPN")
        if (supported.isEmpty()) {
            fail("Samochód nie odpowiada. Włącz zapłon lub silnik i połącz ponownie.")
            return
        }
        log.add("Obsługiwane PID: " + supported.joinToString(" ") { Obd.hex(it) })
        for (pid in listOf(0x5E, 0x9D, 0x10, 0x24, 0x34, 0x44, 0x4F)) {
            log.add("PID ${Obd.hex(pid)}: " + if (pid in supported) "TAK" else "nie")
        }

        // Maksimum skali lambdy (0 = standardowe).
        val maxRatio = if (0x4F in supported) {
            Obd.payloads(ask(Obd.command(0x4F)).orEmpty(), 0x4F).firstOrNull()?.getOrNull(0) ?: 0
        } else {
            0
        }

        onStatus("Jednorazowy odczyt wszystkich parametrów...")
        for (pid in supported) {
            if (stopped) return
            if (pid % 0x20 == 0) continue
            val response = ask(Obd.command(pid)) ?: continue
            val value = Obd.payloads(response, pid).firstOrNull()?.let { Obd.describe(pid, it, maxRatio) }
            if (value != null) log.add("    = $value")
        }

        val live = Obd.LIVE_PIDS.filter { it in supported }
        if (live.isEmpty()) {
            fail("Brak parametrów do odczytu na żywo.")
            return
        }
        onStatus("Odczyt na żywo. Zakończ przyciskiem Rozłącz.")
        log.add("--- ODCZYT NA ŻYWO ---")
        readLive(live, maxRatio)
    }

    private fun openWorkingChannel(channels: List<ElmBleClient.Channel>): Boolean {
        for (channel in channels) {
            log.add("Próba kanału: $channel")
            if (!client.open(channel)) continue
            // Pierwsze polecenie po połączeniu bywa gubione, stąd dwie próby.
            repeat(2) {
                if (ask("ATI", 2500) != null) return true
            }
        }
        return false
    }

    private fun readSupportedPids(): Set<Int> {
        val all = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0 && !stopped) {
            // Pierwsze zapytanie trwa dłużej, bo adapter szuka protokołu.
            val response = ask(Obd.command(base), if (base == 0) 15000 else 3000) ?: break
            val found = Obd.supported(base, Obd.payloads(response, base))
            all.addAll(found)
            if (base + 0x20 !in found) break
            base += 0x20
        }
        return all
    }

    private fun readLive(pids: List<Int>, maxRatio: Int) {
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        var silentRounds = 0
        while (!stopped && client.isConnected) {
            val data = linkedMapOf<Int, IntArray>()
            for (pid in pids) {
                val response = client.send(Obd.command(pid), 2000) ?: continue
                Obd.payloads(response, pid).firstOrNull()?.let { data[pid] = it }
            }
            if (data.isEmpty()) {
                silentRounds++
                onLive("Brak danych (silnik wyłączony?)")
                if (silentRounds == 1) log.add("${clock.format(Date())} brak danych")
                Thread.sleep(1000)
                continue
            }
            silentRounds = 0

            val lines = data.mapNotNull { (pid, bytes) -> Obd.describe(pid, bytes, maxRatio) }.toMutableList()
            val estimate = dieselEstimate(data, maxRatio)
            if (estimate != null) lines.add("szacunek z MAF i lambdy: ${Obd.num(estimate)} l/h")
            onLive(lines.joinToString("\n"))

            val raw = data.entries.joinToString(" ") { (pid, bytes) ->
                Obd.hex(pid) + "=" + bytes.joinToString("") { Obd.hex(it) }
            }
            val note = estimate?.let { " est=${Obd.num(it)}" }.orEmpty()
            log.addLive("${clock.format(Date())} $raw$note")
        }
        if (!stopped) onStatus("Połączenie przerwane.")
    }

    private fun dieselEstimate(data: Map<Int, IntArray>, maxRatio: Int): Double? {
        val maf = data[0x10]?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 100.0 } ?: return null
        // Tylko zmierzona lambda (24 lub 34), nie zadana (44).
        val bytes = data[0x24] ?: data[0x34] ?: return null
        val lambda = Obd.lambda(bytes, maxRatio) ?: return null
        return Obd.dieselLitersPerHour(maf, lambda)
    }

    /** Wysyła polecenie i zapisuje pytanie oraz odpowiedź w logu. */
    private fun ask(command: String, timeoutMs: Long = 3000): String? {
        if (stopped) throw InterruptedException()
        val response = client.send(command, timeoutMs)
        val shown = response?.replace('\r', '|')?.replace("\n", "") ?: "(brak odpowiedzi)"
        log.add("$command -> $shown")
        return response
    }

    private fun fail(message: String) {
        log.add(message)
        onStatus(message)
    }
}
