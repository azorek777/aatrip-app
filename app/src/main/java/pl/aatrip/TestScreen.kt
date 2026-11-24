package pl.aatrip

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Etap 1: ekran testowy z ZMYŚLONYMI danymi.
 * Sprawdza, czy aplikacja pojawia się w aucie, czy działają przyciski
 * i czy liczby odświeżają się na żywo.
 */
class TestScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var seconds = 0

    private val tick = object : Runnable {
        override fun run() {
            seconds++
            invalidate()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
            }
        })
    }

    private fun toggle() {
        running = !running
        if (running) {
            seconds = 0
            handler.postDelayed(tick, 1000)
        } else {
            handler.removeCallbacks(tick)
        }
        invalidate()
    }

    @Suppress("DEPRECATION") // setTitle i setHeaderAction działają na każdym poziomie Car API.
    override fun onGetTemplate(): Template {
        // Symulacja: stała prędkość 72 km/h i spalanie 5,0 l/100 km.
        val distanceKm = seconds * 0.02
        val liters = distanceKm * 0.05
        val cost = liters * 6.00

        // Tytuły wierszy muszą być stałe, wtedy Android Auto traktuje zmianę jako odświeżenie.
        val pane = Pane.Builder()
            .addRow(row("Dystans", format("%.2f km", distanceKm)))
            .addRow(row("Spalanie: aktualne / średnie", "5,0 / 5,0 l/100 km"))
            .addRow(row("Paliwo i koszt", format("%.3f l / %.2f zł", liters, cost)))
            .addRow(row("TEST (dane zmyślone)", if (running) "Licznik: $seconds s" else "Zatrzymany"))
            .addAction(
                Action.Builder()
                    .setTitle(if (running) "Zatrzymaj" else "Start")
                    .setOnClickListener { toggle() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("AA Trip")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(title: String, value: String): Row =
        Row.Builder().setTitle(title).addText(value).build()

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.forLanguageTag("pl-PL"), pattern, *args)
}
