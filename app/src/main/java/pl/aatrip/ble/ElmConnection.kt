package pl.aatrip.ble

/**
 * Łączy się i wybiera kanał, na którym adapter odpowiada.
 * Blokuje, więc tylko z wątku roboczego.
 */
fun ElmBleClient.connectAndOpen(): Boolean {
    for (channel in connect()) {
        if (!open(channel)) continue
        // Pierwsze polecenie po połączeniu bywa gubione, stąd dwie próby.
        repeat(2) {
            if (send("ATI", 2500) != null) return true
        }
    }
    return false
}
