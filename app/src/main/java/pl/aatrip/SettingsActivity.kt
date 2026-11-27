package pl.aatrip

import android.app.Activity
import android.os.Bundle
import android.text.method.DigitsKeyListener
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import pl.aatrip.trip.Format

/** Cena paliwa, próg wznowienia trasy i korekta spalania. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        val root = column()

        label(root, "Ustawienia", 26f)
        val price = field(root, "Cena paliwa (zł za litr)", Format.number(settings.pricePerLiter, 2))
        val minutes = field(root, "Wznów trasę po postoju krótszym niż (minuty)", settings.resumeMinutes.toString())
        val factor = field(root, "Korekta spalania (1,00 = bez zmian)", Format.number(settings.fuelFactor, 2))
        label(
            root,
            "Korekta: litry z dystrybutora podzielone przez litry z aplikacji, razy obecna korekta. " +
                "Cena i korekta dotyczą tras rozpoczętych po zapisaniu.",
            14f,
        )

        button(root, "Zapisz") {
            val newPrice = decimal(price)
            val newMinutes = minutes.text.toString().trim().toIntOrNull()
            val newFactor = decimal(factor)
            val error = when {
                newPrice == null || newPrice !in 0.01..100.0 -> "Podaj cenę, np. 6,15"
                newMinutes == null || newMinutes !in 1..240 -> "Minuty: od 1 do 240"
                newFactor == null || newFactor !in 0.3..3.0 -> "Korekta: od 0,30 do 3,00"
                else -> null
            }
            if (error != null) {
                toast(error)
            } else {
                settings.pricePerLiter = newPrice!!
                settings.resumeMinutes = newMinutes!!
                settings.fuelFactor = newFactor!!
                toast("Zapisano")
                finish()
            }
        }
    }

    private fun field(root: LinearLayout, title: String, value: String): EditText {
        label(root, title)
        val input = EditText(this)
        input.setSingleLine()
        // Polska klawiatura daje przecinek, który zwykłe pole liczbowe odrzuca.
        input.keyListener = DigitsKeyListener.getInstance("0123456789,.")
        input.setText(value)
        root.addView(input)
        return input
    }

    private fun decimal(input: EditText): Double? =
        input.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
