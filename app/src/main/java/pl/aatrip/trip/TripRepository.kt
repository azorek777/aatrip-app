package pl.aatrip.trip

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Jedyne miejsce, które trzyma trasy. Dostęp tylko przez [use], więc usługa
 * i ekrany nie wchodzą sobie w drogę. Zapis do pliku jest atomowy.
 */
object TripRepository {
    private var recorder: TripRecorder? = null

    @Synchronized
    fun <T> use(context: Context, block: (TripRecorder) -> T): T =
        block(recorder ?: load(context).also { recorder = it })

    @Synchronized
    fun save(context: Context) {
        val data = recorder ?: return
        val json = JSONObject()
        data.current?.let { json.put("current", toJson(it)) }
        json.put("history", JSONArray(data.history.map { toJson(it) }))

        val file = AtomicFile(file(context))
        try {
            val stream = file.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: IOException) {
            Log.e(TAG, "Nie udało się zapisać tras", e)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "trips.json")

    private fun load(context: Context): TripRecorder {
        val file = file(context)
        return try {
            val text = String(AtomicFile(file).readFully(), Charsets.UTF_8)
            val json = JSONObject(text)
            val array = json.getJSONArray("history")
            TripRecorder(
                current = json.optJSONObject("current")?.let { fromJson(it) },
                history = (0 until array.length()).map { fromJson(array.getJSONObject(it)) },
            )
        } catch (e: java.io.FileNotFoundException) {
            TripRecorder()
        } catch (e: Exception) {
            // Uszkodzonego pliku nie nadpisujemy bez kopii.
            Log.e(TAG, "Nie udało się odczytać tras", e)
            if (file.exists()) file.copyTo(File(file.path + ".broken"), overwrite = true)
            TripRecorder()
        }
    }

    private fun toJson(t: Trip) = JSONObject()
        .put("startedAt", t.startedAt)
        .put("lastActivity", t.lastActivity)
        .put("pricePerLiter", t.pricePerLiter)
        .put("fuelFactor", t.fuelFactor)
        .put("activeSeconds", t.activeSeconds)
        .put("distanceKm", t.distanceKm)
        .put("measuredLiters", t.measuredLiters)
        .put("measuredAirGrams", t.measuredAirGrams)
        .put("unmeasuredAirGrams", t.unmeasuredAirGrams)
        .put("paused", t.paused)

    private fun fromJson(o: JSONObject) = Trip(
        startedAt = o.getLong("startedAt"),
        lastActivity = o.getLong("lastActivity"),
        pricePerLiter = o.getDouble("pricePerLiter"),
        fuelFactor = o.getDouble("fuelFactor"),
        activeSeconds = o.getDouble("activeSeconds"),
        distanceKm = o.getDouble("distanceKm"),
        measuredLiters = o.getDouble("measuredLiters"),
        measuredAirGrams = o.getDouble("measuredAirGrams"),
        unmeasuredAirGrams = o.getDouble("unmeasuredAirGrams"),
        paused = o.optBoolean("paused", true),
    )

    private const val TAG = "TripRepository"
}
