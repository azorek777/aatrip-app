package pl.aatrip.obd

import java.util.Locale

/** Czysta logika OBD-II (tryb 01), bez zależności od Androida. */
object Obd {

    /** Parametry, które odczytujemy w pętli, o ile samochód je zgłasza. */
    val LIVE_PIDS = listOf(0x0C, 0x0D, 0x10, 0x24, 0x34, 0x44, 0x04, 0x0B, 0x23, 0x5E, 0x9D)

    fun command(pid: Int): String = "01" + hex(pid)

    fun hex(value: Int): String = "%02X".format(Locale.ROOT, value)

    /**
     * Zwraca bajty danych z każdej linii odpowiedzi pasującej do PID-u
     * (kilka sterowników może odpowiedzieć na to samo pytanie).
     */
    fun payloads(response: String, pid: Int): List<IntArray> {
        val prefix = "41" + hex(pid)
        return response.split('\r', '\n').mapNotNull { raw ->
            val line = raw.filterNot { it.isWhitespace() || it == '>' }.uppercase(Locale.ROOT)
            if (!line.startsWith(prefix) || line.length % 2 != 0) return@mapNotNull null
            val data = line.substring(prefix.length)
            if (data.isEmpty() || data.any { it !in "0123456789ABCDEF" }) return@mapNotNull null
            IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
        }
    }

    /** Dekoduje maskę z PID 00, 20, 40...: najstarszy bit to PID base+1. */
    fun supported(base: Int, payloads: List<IntArray>): Set<Int> {
        val result = sortedSetOf<Int>()
        for (bytes in payloads) {
            if (bytes.size < 4) continue
            for (i in 0 until 32) {
                if (bytes[i / 8] and (0x80 shr (i % 8)) != 0) result.add(base + i + 1)
            }
        }
        return result
    }

    /**
     * Lambda (współczynnik nadmiaru powietrza) z PID 24, 34 lub 44.
     * Zwraca null, gdy odczyt dobił do końca skali.
     * [maxRatio] to bajt A z PID 4F; 0 oznacza standardową skalę do 2.
     */
    fun lambda(bytes: IntArray, maxRatio: Int = 0): Double? {
        if (bytes.size < 2) return null
        val raw = bytes[0] * 256 + bytes[1]
        if (raw >= 0xFFF0) return null
        return if (maxRatio > 0) raw * maxRatio / 65535.0 else raw * 2.0 / 65536.0
    }

    /** Czytelna wartość parametru albo null, gdy nie umiemy go zdekodować. */
    fun describe(pid: Int, b: IntArray, maxRatio: Int = 0): String? {
        val a = b.getOrNull(0) ?: return null
        val ab = b.getOrNull(1)?.let { a * 256 + it }
        return when (pid) {
            0x04 -> "obciążenie ${num(a * 100 / 255.0)} %"
            0x05 -> "płyn chłodzący ${a - 40} °C"
            0x0B -> "ciśnienie w dolocie $a kPa"
            0x0C -> ab?.let { "obroty ${it / 4} obr/min" }
            0x0D -> "prędkość $a km/h"
            0x0F -> "temperatura dolotu ${a - 40} °C"
            0x10 -> ab?.let { "MAF ${num(it / 100.0)} g/s" }
            0x1F -> ab?.let { "czas od uruchomienia $it s" }
            0x23 -> ab?.let { "ciśnienie na listwie ${it * 10} kPa" }
            0x24, 0x34, 0x44 -> if (ab == null) null else {
                "lambda " + (lambda(b, maxRatio)?.let { num(it) } ?: "poza skalą")
            }
            0x2F -> "poziom paliwa ${num(a * 100 / 255.0)} %"
            0x31 -> ab?.let { "dystans od kasowania błędów $it km" }
            0x5E -> ab?.let { "zużycie paliwa ${num(it / 20.0)} l/h" }
            0x9D -> ab?.let { "zużycie paliwa ${num(it / 50.0)} g/s" }
            else -> null
        }
    }

    /**
     * Przybliżone zużycie oleju napędowego z przepływu powietrza i lambdy.
     * 14,5 to stechiometryczny stosunek powietrza do paliwa, 835 g to masa litra.
     */
    fun dieselLitersPerHour(mafGramsPerSecond: Double, lambda: Double): Double? {
        if (lambda <= 0.0) return null
        return mafGramsPerSecond / (14.5 * lambda) * 3600.0 / 835.0
    }

    /**
     * Spalanie w l/h z MAF i surowych bajtów lambdy (PID 24).
     * null: sonda nie podaje wyniku (zera po zimnym starcie).
     * 0: lambda na końcu skali, czyli odcięcie paliwa przy hamowaniu silnikiem.
     */
    fun fuelRate(mafGramsPerSecond: Double, lambdaBytes: IntArray, maxRatio: Int): Double? {
        if (lambdaBytes.size < 2) return null
        val raw = lambdaBytes[0] * 256 + lambdaBytes[1]
        if (raw == 0) return null
        val ratio = lambda(lambdaBytes, maxRatio) ?: return 0.0
        return dieselLitersPerHour(mafGramsPerSecond, ratio)
    }

    fun num(value: Double): String = "%.2f".format(Locale.ROOT, value)
}
