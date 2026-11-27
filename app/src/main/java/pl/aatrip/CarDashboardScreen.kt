package pl.aatrip

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import pl.aatrip.trip.Format
import pl.aatrip.trip.LiveState
import pl.aatrip.trip.TripRepository
import pl.aatrip.trip.TripService

/** Dashboard w samochodzie: trzy wiersze i przycisk Start albo Zatrzymaj. */
class CarDashboardScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                resumeOpenTrip()
                handler.postDelayed(tick, 1000)
            }

            override fun onStop(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
            }
        })
    }

    /** Po ponownym otwarciu w aucie kontynuuje niezakończoną trasę. */
    private fun resumeOpenTrip() {
        val resumeMs = AppSettings(carContext).resumeMs
        val (expired, open) = TripRepository.use(carContext) {
            val closed = it.expire(System.currentTimeMillis(), resumeMs)
            closed to (it.current != null)
        }
        if (expired) TripRepository.save(carContext)
        if (open && !LiveState.serviceRunning) TripService.connect(carContext)?.let { toast(it) }
    }

    @Suppress("DEPRECATION") // setTitle i setHeaderAction działają na każdym poziomie Car API.
    override fun onGetTemplate(): Template {
        var distance = "—"
        var consumption = "— / —"
        var fuel = "—"
        var open = false
        TripRepository.use(carContext) { recorder ->
            val trip = recorder.current
            if (trip != null) {
                open = true
                distance = Format.distance(trip.distanceKm)
                val now = Format.currentConsumption(recorder.currentLitersPerHour(), recorder.currentSpeedKmh())
                consumption = now + " / " + Format.average(trip.averagePer100)
                fuel = Format.fuelAndCost(trip)
            }
        }
        val status = when {
            LiveState.status.isNotEmpty() -> LiveState.status
            open -> "wstrzymana"
            else -> "naciśnij Start"
        }

        // Tytuły wierszy muszą być stałe, wtedy Android Auto traktuje zmianę jako odświeżenie.
        // Najwyżej 3 wiersze, żeby nie trzeba było przewijać.
        val pane = Pane.Builder()
            .addRow(row("Dystans", "$distance · $status"))
            .addRow(row("Spalanie: aktualne / średnie", consumption))
            .addRow(row("Paliwo i koszt", fuel))
            .addAction(
                Action.Builder()
                    .setTitle(if (open) "Zatrzymaj" else "Start")
                    .setOnClickListener { toggle(open) }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("AA Trip")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun toggle(open: Boolean) {
        if (open) {
            val saved = TripService.stopTrip(carContext)
            toast(if (saved) "Trasa zapisana w historii" else "Zakończono. Trasa była pusta")
        } else {
            TripService.startTrip(carContext)?.let { toast(it) }
        }
        invalidate()
    }

    private fun toast(message: String) =
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()

    private fun row(title: String, value: String): Row =
        Row.Builder().setTitle(title).addText(value).build()
}
