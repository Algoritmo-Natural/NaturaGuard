package com.algoritmonatural.naturaguard.shared

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Niveis iguais aos do netguard (critico/aviso/info) para juntar registos
 * do Termux e desta app sem traducao.
 */
enum class Severity(val wireValue: String, val rank: Int) {
    CRITICAL("critico", 3),
    WARNING("aviso", 2),
    INFO("info", 1);

    companion object {
        fun fromWire(value: String): Severity = values().firstOrNull { it.wireValue == value } ?: INFO
    }
}

data class SecurityEvent(
    val type: String,
    val severity: Severity,
    val message: String,
    val source: String,
    val extra: Map<String, String> = emptyMap(),
)

/** Um alerta guardado; `marked` = o utilizador ja o viu e marcou. */
data class Alert(
    val id: String,
    val timestamp: String,
    val type: String,
    val severity: Severity,
    val message: String,
    val source: String,
    val marked: Boolean,
)

/**
 * events.jsonl (so acrescenta) + marks.txt (ids marcados, so acrescenta).
 * Marcar nunca reescreve o registo de alertas.
 */
class EventLogger(context: Context) {
    private val eventsFile = File(context.filesDir, "events.jsonl")
    private val marksFile = File(context.filesDir, "marks.txt")

    @Synchronized
    fun log(event: SecurityEvent): String {
        rotateIfBig()
        val id = UUID.randomUUID().toString()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val json = JSONObject().apply {
            put("id", id)
            put("timestamp", format.format(Date()))
            put("type", event.type)
            put("severity", event.severity.wireValue)
            put("message", event.message)
            put("source", event.source)
            if (event.extra.isNotEmpty()) put("extra", JSONObject(event.extra))
        }
        eventsFile.appendText(json.toString() + "\n")
        return id
    }

    @Synchronized
    fun mark(id: String) {
        if (id.matches(ID_PATTERN)) marksFile.appendText(id + "\n")
    }

    /** Mais recentes primeiro. Linhas corrompidas sao ignoradas. */
    @Synchronized
    fun alerts(limit: Int = 100): List<Alert> {
        if (!eventsFile.exists()) return emptyList()
        val marked = if (marksFile.exists()) marksFile.readLines().toHashSet() else emptySet<String>()
        return eventsFile.readLines().asReversed().mapNotNull { line ->
            try {
                val o = JSONObject(line)
                val id = o.getString("id")
                Alert(
                    id = id,
                    timestamp = o.getString("timestamp"),
                    type = o.getString("type"),
                    severity = Severity.fromWire(o.getString("severity")),
                    message = o.getString("message"),
                    source = o.optString("source"),
                    marked = id in marked,
                )
            } catch (_: Exception) {
                null
            }
        }.take(limit)
    }

    fun unmarkedImportantCount(): Int =
        alerts(500).count { !it.marked && it.severity.rank >= Severity.WARNING.rank }

    private fun rotateIfBig() {
        if (eventsFile.exists() && eventsFile.length() > MAX_BYTES) {
            eventsFile.renameTo(File(eventsFile.parentFile, "events.old.jsonl"))
        }
    }

    private companion object {
        const val MAX_BYTES = 1_000_000L
        val ID_PATTERN = Regex("[0-9a-f-]{36}")
    }
}
