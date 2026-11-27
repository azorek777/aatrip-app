package pl.aatrip

import android.app.Activity
import android.os.Bundle
import pl.aatrip.trip.Format
import pl.aatrip.trip.Trip
import pl.aatrip.trip.TripRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista tras, najnowsza na górze. */
class HistoryActivity : Activity() {

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        val resumeMs = AppSettings(this).resumeMs
        var expired = false
        var open: String? = null
        var closed = emptyList<String>()
        TripRepository.use(this) { recorder ->
            expired = recorder.expire(System.currentTimeMillis(), resumeMs)
            open = recorder.current?.let { describe(it) }
            closed = recorder.history.map { describe(it) }
        }
        if (expired) TripRepository.save(this)

        val root = column()
        label(root, "Historia tras", 26f)
        open?.let { label(root, "Niezakończona (można ją jeszcze kontynuować)\n$it") }
        if (open == null && closed.isEmpty()) label(root, "Nie ma jeszcze żadnej trasy.")
        for (text in closed) label(root, text)
    }

    private fun describe(trip: Trip): String {
        val date = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
            .format(Date(trip.startedAt))
        return "$date\n" +
            "Czas: ${Format.duration(trip.activeSeconds)}\n" +
            "Dystans: ${Format.distance(trip.distanceKm)}\n" +
            "Średnie spalanie: ${Format.average(trip.averagePer100)}\n" +
            "Paliwo: ${Format.number(trip.liters, 2)} l\n" +
            "Koszt: ${Format.number(trip.cost, 2)} zł\n" +
            "Cena za litr: ${Format.number(trip.pricePerLiter, 2)} zł"
    }
}
