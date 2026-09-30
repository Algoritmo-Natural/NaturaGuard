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

/** Converte o carimbo UTC gravado para a hora local do telemovel (ex.: Acores). */
fun localTime(utc: String): String = try {
    val parser = SimpleDateFormat(UTC_PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(parser.parse(utc)!!)
} catch (_: Exception) {
    utc
}

private const val UTC_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

/**
 * events.jsonl (so acrescenta) + marks.txt (ids marcados, so acrescenta).
 * Marcar nunca reescreve o registo de alertas. O Worker, o recetor de admin e
 * o ecra criam instancias diferentes, por isso o cadeado e partilhado por todas.
 */
class EventLogger(context: Context) {
    private val dir = context.filesDir
    private val eventsFile = File(dir, "events.jsonl")
    private val oldFile = File(dir, "events.old.jsonl")
    private val marksFile = File(dir, "marks.txt")

    fun log(event: SecurityEvent): String = synchronized(LOCK) {
        rotateIfBig()
        val id = UUID.randomUUID().toString()
        val format = SimpleDateFormat(UTC_PATTERN, Locale.US)
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
        id
    }

    fun mark(id: String) = synchronized(LOCK) {
        if (id.matches(ID_PATTERN)) marksFile.appendText(id + "\n")
    }

    /** Mais recentes primeiro, incluindo o ficheiro rodado. Linhas corrompidas sao ignoradas. */
    fun alerts(limit: Int = 100): List<Alert> = synchronized(LOCK) {
        val marked = if (marksFile.exists()) marksFile.readLines().toHashSet() else emptySet<String>()
        val lines = listOf(oldFile, eventsFile).filter { it.exists() }.flatMap { it.readLines() }
        lines.asReversed().asSequence().mapNotNull { line ->
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
        }.take(limit).toList()
    }

    private fun rotateIfBig() {
        if (eventsFile.exists() && eventsFile.length() > MAX_BYTES) {
            oldFile.delete()
            eventsFile.renameTo(oldFile)
        }
    }

    private companion object {
        val LOCK = Any()
        const val MAX_BYTES = 1_000_000L
        val ID_PATTERN = Regex("[0-9a-f-]{36}")
    }
}
