package pl.aatrip.trip

import java.util.Locale

/** Teksty pokazywane w aucie i w telefonie. */
object Format {
    private val polish: Locale = Locale.forLanguageTag("pl-PL")

    fun number(value: Double, digits: Int): String = String.format(polish, "%.${digits}f", value)

    fun distance(km: Double): String = number(km, 2) + " km"

    /** Podczas jazdy l/100 km, na postoju l/h. */
    fun currentConsumption(litersPerHour: Double?, speedKmh: Double?): String {
        if (litersPerHour == null || speedKmh == null) return "—"
        return if (speedKmh >= 5.0) {
            number(litersPerHour / speedKmh * 100.0, 1) + " l/100 km"
        } else {
            number(litersPerHour, 1) + " l/h"
        }
    }

    fun average(per100: Double?): String = if (per100 == null) "—" else number(per100, 1) + " l/100 km"

    fun fuelAndCost(trip: Trip): String = number(trip.liters, 2) + " l / " + number(trip.cost, 2) + " zł"

    fun duration(seconds: Double): String {
        val minutes = (seconds / 60).toLong()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
}
