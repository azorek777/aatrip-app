package pl.aatrip

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Ekran w telefonie. Na etapie 1 pokazuje, jak system zapisał źródło instalacji. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val report = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(padding, padding * 3, padding, padding)
            text = buildReport()
        }
        setContentView(ScrollView(this).apply { addView(report) })
    }

    private fun buildReport(): String {
        val version = packageManager.getPackageInfo(packageName, 0).versionName

        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = packageManager.getInstallSourceInfo(packageName)
            "Zainstalowana przez (installer):\n${info.installingPackageName ?: "brak"}\n\n" +
                "Instalację zainicjował (initiator):\n${info.initiatingPackageName ?: "brak"}"
        } else {
            @Suppress("DEPRECATION")
            val installer = packageManager.getInstallerPackageName(packageName)
            "Zainstalowana przez (installer):\n${installer ?: "brak"}"
        }

        return "AA Trip $version\nEtap 1: test Android Auto\n\n$source\n\n" +
            "Oczekiwany installer: com.android.vending (Sklep Play)."
    }
}
