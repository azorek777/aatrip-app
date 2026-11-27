package pl.aatrip.trip

/** Jedna trasa. Czysta logika bez zależności od Androida. */
data class Trip(
    /** Czas rozpoczęcia (ms od 1970), jednocześnie identyfikator. */
    val startedAt: Long,
    /** Ostatni odczyt z pracującego silnika. Od niego liczy się próg wznowienia. */
    var lastActivity: Long,
    /** Cena i korekta z dnia przejazdu, żeby historia nie zmieniała się wstecz. */
    val pricePerLiter: Double,
    val fuelFactor: Double,
    var activeSeconds: Double = 0.0,
    var distanceKm: Double = 0.0,
    /** Paliwo policzone w chwilach, gdy sonda lambda podawała wynik. */
    var measuredLiters: Double = 0.0,
    var measuredAirGrams: Double = 0.0,
    /** Powietrze zassane, gdy sonda jeszcze nie działała (zimny silnik). */
    var unmeasuredAirGrams: Double = 0.0,
    var paused: Boolean = true,
) {
    /** Litry na gram powietrza: z tej trasy, a zanim będzie dość danych, założone. */
    private val litersPerGramOfAir: Double
        get() = if (measuredAirGrams >= MIN_MEASURED_AIR) {
            measuredLiters / measuredAirGrams
        } else {
            DEFAULT_LITERS_PER_GRAM
        }

    private val rawEstimatedLiters: Double get() = unmeasuredAirGrams * litersPerGramOfAir

    val liters: Double get() = (measuredLiters + rawEstimatedLiters) * fuelFactor

    val cost: Double get() = liters * pricePerLiter

    /** l/100 km; null, dopóki dystans jest zbyt mały, żeby wynik miał sens. */
    val averagePer100: Double? get() = if (distanceKm >= 0.1) liters / distanceKm * 100.0 else null

    /** Jaka część paliwa pochodzi z szacunku bez sondy (0..1). */
    val estimatedShare: Double
        get() {
            val total = measuredLiters + rawEstimatedLiters
            return if (total > 0) rawEstimatedLiters / total else 0.0
        }

    /** Przypadkowe Start/Zatrzymaj bez jazdy i bez spalania nie trafia do historii. */
    val worthKeeping: Boolean get() = distanceKm >= 0.05 || liters >= 0.01

    companion object {
        const val MIN_MEASURED_AIR = 200.0

        /** Założenie na zimny start: lambda 3,5, AFR 14,5, 835 g oleju napędowego na litr. */
        const val DEFAULT_LITERS_PER_GRAM = 1.0 / (14.5 * 3.5 * 835.0)
    }
}
