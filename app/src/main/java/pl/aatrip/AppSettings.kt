package pl.aatrip

import android.content.Context

/** Ustawienia aplikacji. Liczby trzymamy jako tekst, żeby nie tracić dokładności. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var pricePerLiter: Double
        get() = prefs.getString("price", null)?.toDoubleOrNull() ?: 6.00
        set(value) = prefs.edit().putString("price", value.toString()).apply()

    var resumeMinutes: Int
        get() = prefs.getInt("resumeMinutes", 15)
        set(value) = prefs.edit().putInt("resumeMinutes", value).apply()

    val resumeMs: Long get() = resumeMinutes * 60_000L

    var fuelFactor: Double
        get() = prefs.getString("fuelFactor", null)?.toDoubleOrNull() ?: 1.0
        set(value) = prefs.edit().putString("fuelFactor", value.toString()).apply()

    val scannerAddress: String? get() = prefs.getString("scannerAddress", null)

    val scannerName: String? get() = prefs.getString("scannerName", null)

    fun saveScanner(address: String, name: String) =
        prefs.edit().putString("scannerAddress", address).putString("scannerName", name).apply()
}
