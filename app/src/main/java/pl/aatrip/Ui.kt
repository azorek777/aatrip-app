package pl.aatrip

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Proste elementy ekranów w telefonie, budowane w kodzie. */

/** Przewijana kolumna jako zawartość ekranu. */
fun Activity.column(): LinearLayout {
    val padding = (16 * resources.displayMetrics.density).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    // fitsSystemWindows: treść nie wchodzi pod pasek stanu i nawigacji.
    val scroll = ScrollView(this).apply {
        fitsSystemWindows = true
        addView(root)
    }
    setContentView(scroll)
    return root
}

fun Activity.label(parent: LinearLayout, text: String = "", size: Float = 16f): TextView {
    val view = TextView(this)
    view.text = text
    view.textSize = size
    view.setPadding(0, 16, 0, 16)
    view.setTextIsSelectable(true)
    parent.addView(view)
    return view
}

fun Activity.button(parent: LinearLayout, title: String, action: () -> Unit): Button {
    val view = Button(this)
    view.text = title
    view.isAllCaps = false
    view.setOnClickListener { action() }
    parent.addView(view)
    return view
}
