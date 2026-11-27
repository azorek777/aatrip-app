package pl.aatrip.trip

/** Jeden komplet odczytów z pracującego silnika. */
class Sample(
    val wallMs: Long,
    /** Zegar monotoniczny, odporny na zmianę godziny w telefonie. */
    val monoMs: Long,
    val speedKmh: Int,
    val mafGramsPerSecond: Double,
    /** null, gdy sonda lambda nie podaje wyniku. */
    val litersPerHour: Double?,
)

/**
 * Zasady nagrywania: Start tworzy trasę, odczyty ją wypełniają, zgaszenie silnika
 * wstrzymuje, powrót przed upływem progu kontynuuje, a Zatrzymaj zamyka na stałe.
 */
class TripRecorder(
    current: Trip? = null,
    history: List<Trip> = emptyList(),
) {
    var current: Trip? = current
        private set

    private val closed = history.toMutableList()

    /** Zamknięte trasy, najnowsza na początku. */
    val history: List<Trip> get() = closed

    private var previous: Sample? = null
    private val recent = ArrayDeque<Sample>()

    fun start(nowMs: Long, pricePerLiter: Double, fuelFactor: Double) {
        if (current != null) return
        current = Trip(nowMs, nowMs, pricePerLiter, fuelFactor)
        forgetSamples()
    }

    /** Zwraca true, jeśli ten odczyt zamknął przeterminowaną trasę. */
    fun sample(s: Sample, resumeMs: Long): Boolean {
        if (expire(s.wallMs, resumeMs)) return true
        val trip = current ?: return false

        val before = previous
        if (before != null) {
            val seconds = (s.monoMs - before.monoMs) / 1000.0
            // Dłuższej luki nie zgadujemy.
            if (seconds > 0 && seconds <= MAX_GAP_SECONDS) {
                trip.activeSeconds += seconds
                trip.distanceKm += (before.speedKmh + s.speedKmh) / 2.0 * seconds / 3600.0
                val air = (before.mafGramsPerSecond + s.mafGramsPerSecond) / 2.0 * seconds
                val rate = s.litersPerHour
                if (rate != null) {
                    // Paliwo liczone z bieżącego odczytu, powietrze z tego samego.
                    trip.measuredLiters += rate * seconds / 3600.0
                    trip.measuredAirGrams += s.mafGramsPerSecond * seconds
                } else {
                    trip.unmeasuredAirGrams += air
                }
            }
        }
        previous = s
        recent.addLast(s)
        while (recent.size > SMOOTHING) recent.removeFirst()
        trip.paused = false
        trip.lastActivity = s.wallMs
        return false
    }

    /** Silnik zgaszony albo utracone połączenie. */
    fun pause() {
        current?.paused = true
        forgetSamples()
    }

    /** Zamyka trasę, jeśli minął próg wznowienia. Zwraca true, gdy zamknął. */
    fun expire(nowMs: Long, resumeMs: Long): Boolean {
        val trip = current ?: return false
        if (nowMs - trip.lastActivity <= resumeMs) return false
        stop()
        return true
    }

    fun stop() {
        val trip = current ?: return
        trip.paused = false
        if (trip.worthKeeping) closed.add(0, trip)
        current = null
        forgetSamples()
    }

    /** Wygładzone spalanie chwilowe w l/h. */
    fun currentLitersPerHour(): Double? {
        val values = recent.mapNotNull { it.litersPerHour }
        return if (values.isEmpty()) null else values.sum() / values.size * (current?.fuelFactor ?: 1.0)
    }

    fun currentSpeedKmh(): Double? =
        if (recent.isEmpty()) null else recent.sumOf { it.speedKmh }.toDouble() / recent.size

    private fun forgetSamples() {
        previous = null
        recent.clear()
    }

    private companion object {
        const val MAX_GAP_SECONDS = 5.0
        const val SMOOTHING = 4
    }
}
