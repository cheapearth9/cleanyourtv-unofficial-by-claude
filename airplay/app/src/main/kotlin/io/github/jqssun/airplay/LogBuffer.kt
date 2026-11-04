package io.github.jqssun.airplay

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * cleanyourtv: the server's latest log lines, kept in memory by the service itself so the launcher's "AirPlay settings"
 * can show them without this app's own screen being open (that screen only collects logs while it is on display).
 */
object LogBuffer {
    private const val MAX_LINES = 1500

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun add(msg: String) {
        val line = "${format.get()!!.format(Date())} $msg"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.pollFirst()
            lines.addLast(line)
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    /** The newest lines that fit in [maxChars] (a broadcast answer has to stay small), oldest first. */
    fun tail(maxChars: Int): String {
        val picked = ArrayList<String>()
        synchronized(lock) {
            var size = 0
            val newestFirst = lines.descendingIterator()
            while (newestFirst.hasNext()) {
                val line = newestFirst.next()
                if (size + line.length + 1 > maxChars) break
                picked.add(line)
                size += line.length + 1
            }
        }
        picked.reverse()
        return picked.joinToString("\n")
    }
}
