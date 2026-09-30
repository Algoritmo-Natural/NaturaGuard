package com.algoritmonatural.naturaguard.scan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Resultado de ler a referencia: nunca confundir "nao existe" com "estragada". */
sealed class Loaded {
    object Missing : Loaded()
    object Corrupted : Loaded()
    data class Ok(val snapshot: Snapshot) : Loaded()
}

/** Guarda a ultima fotografia em armazenamento privado da app. */
class SnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan", Context.MODE_PRIVATE)

    fun load(): Loaded {
        val text = prefs.getString("snapshot", null) ?: return Loaded.Missing
        return try {
            val o = JSONObject(text)
            // opt* com valores por omissao: campos novos de versoes futuras nao estragam a referencia.
            Loaded.Ok(
                Snapshot(
                    accessibility = set(o, "accessibility"),
                    admins = set(o, "admins"),
                    listeners = set(o, "listeners"),
                    sideloaded = set(o, "sideloaded"),
                    adbEnabled = o.optBoolean("adb", false),
                    devOptions = o.optBoolean("dev", false),
                    deviceSecure = o.optBoolean("secure", true),
                    patchOld = o.optBoolean("patchOld", false),
                    rootSuspected = o.optBoolean("root", false),
                )
            )
        } catch (_: Exception) {
            Loaded.Corrupted
        }
    }

    fun save(s: Snapshot) {
        val o = JSONObject()
            .put("version", 1)
            .put("accessibility", JSONArray(s.accessibility.toList()))
            .put("admins", JSONArray(s.admins.toList()))
            .put("listeners", JSONArray(s.listeners.toList()))
            .put("sideloaded", JSONArray(s.sideloaded.toList()))
            .put("adb", s.adbEnabled)
            .put("dev", s.devOptions)
            .put("secure", s.deviceSecure)
            .put("patchOld", s.patchOld)
            .put("root", s.rootSuspected)
        prefs.edit().putString("snapshot", o.toString()).apply()
    }

    private fun set(o: JSONObject, key: String): Set<String> {
        val a = o.optJSONArray(key) ?: return emptySet()
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }
}
