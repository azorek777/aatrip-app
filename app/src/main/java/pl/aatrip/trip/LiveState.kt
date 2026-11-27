package pl.aatrip.trip

/** Bieżący stan usługi, czytany przez ekran w aucie i w telefonie. */
object LiveState {
    @Volatile var serviceRunning = false

    /** Diagnostyka zajmuje skaner, więc usługa nie może się wtedy łączyć. */
    @Volatile var diagnosticsRunning = false

    /** Krótki opis, np. "nagrywanie" albo "łączenie". */
    @Volatile var status = ""

    /** Surowe wartości do podglądu i kalibracji. */
    @Volatile var details = ""
}
