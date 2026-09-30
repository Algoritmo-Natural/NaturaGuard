package com.algoritmonatural.naturaguard.scan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda a ultima fotografia em armazenamento privado da app. */
class SnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan", Context.MODE_PRIVATE)

    fun load(): Snapshot? {
        val text = prefs.getString("snapshot", null) ?: return null
        return try {
            val o = JSONObject(text)
            Snapshot(
                accessibility = set(o, "accessibility"),
                admins = set(o, "admins"),
                listeners = set(o, "listeners"),
                sideloaded = set(o, "sideloaded"),
                adbEnabled = o.getBoolean("adb"),
                devOptions = o.getBoolean("dev"),
                deviceSecure = o.getBoolean("secure"),
                patchOld = o.getBoolean("patchOld"),
                rootSuspected = o.getBoolean("root"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(s: Snapshot) {
        val o = JSONObject()
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
        val a = o.getJSONArray(key)
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }
}
