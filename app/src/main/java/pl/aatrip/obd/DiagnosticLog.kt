package pl.aatrip.obd

/**
 * Log diagnostyczny trzymany w pamięci. Część wstępna zostaje w całości,
 * a z odczytów na żywo zachowujemy tylko najnowsze, żeby dało się go udostępnić.
 */
class DiagnosticLog {
    private val head = ArrayList<String>()
    private val live = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (head.size < MAX_HEAD) head.add(line)
    }

    @Synchronized
    fun addLive(line: String) {
        live.addLast(line)
        while (live.size > MAX_LIVE) live.removeFirst()
    }

    @Synchronized
    fun clear() {
        head.clear()
        live.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = head.isEmpty() && live.isEmpty()

    @Synchronized
    fun text(): String = (head + live).joinToString("\n")

    @Synchronized
    fun tail(count: Int): String = (head + live).takeLast(count).joinToString("\n")

    private companion object {
        const val MAX_HEAD = 600
        const val MAX_LIVE = 700
    }
}
