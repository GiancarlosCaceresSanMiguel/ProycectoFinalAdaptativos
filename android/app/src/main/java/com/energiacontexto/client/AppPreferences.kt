package com.energiacontexto.client

import android.content.Context
import com.energiacontexto.client.LatLon
import org.json.JSONArray
import org.json.JSONObject

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("energia_contexto", Context.MODE_PRIVATE)

    fun loadCfg(): SessionConfig {
        val o = prefs.getString("ec_cfg", null)?.let { JSONObject(it) }
        return SessionConfig(
            profile = o?.optString("profile", "balance") ?: "balance",
            mode = o?.optString("mode", "adaptive") ?: "adaptive",
            source = o?.optString("source", "real") ?: "real",
            simSpeed = o?.optInt("speed", 60) ?: 60,
            simStart = o?.optDouble("start", 90.0) ?: 90.0,
        )
    }

    fun saveCfg(cfg: SessionConfig) {
        prefs.edit().putString(
            "ec_cfg",
            JSONObject()
                .put("profile", cfg.profile)
                .put("mode", cfg.mode)
                .put("source", cfg.source)
                .put("speed", cfg.simSpeed)
                .put("start", cfg.simStart)
                .toString(),
        ).apply()
    }

    fun loadZones(): List<LatLon> {
        val arr = prefs.getString("ec_zones", null)?.let { JSONArray(it) } ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val z = arr.getJSONObject(i)
                add(LatLon(z.getDouble("lat"), z.getDouble("lon")))
            }
        }
    }

    fun saveZones(zones: List<LatLon>) {
        val arr = JSONArray()
        zones.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        prefs.edit().putString("ec_zones", arr.toString()).apply()
    }

    fun loadServerUrl(): String = prefs.getString("ec_server", "") ?: ""

    fun saveServerUrl(url: String) {
        prefs.edit().putString("ec_server", url.trim()).apply()
    }

    fun savePending(p: PendingSession?) {
        if (p == null) {
            prefs.edit().remove("ec_pending").apply()
            return
        }
        val ev = JSONArray()
        p.events.forEach { ev.put(it) }
        prefs.edit().putString(
            "ec_pending",
            JSONObject()
                .put("sessionId", p.meta.sessionId)
                .put("mode", p.meta.mode)
                .put("profile", p.meta.profile)
                .put("events", ev)
                .toString(),
        ).apply()
    }

    fun loadPending(): PendingSession? {
        val raw = prefs.getString("ec_pending", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val meta = SessionEngine.SessionMeta(
                o.getString("sessionId"),
                o.getString("mode"),
                o.getString("profile"),
            )
            val arr = o.getJSONArray("events")
            val events = buildList {
                for (i in 0 until arr.length()) add(arr.getJSONObject(i))
            }
            PendingSession(meta, events)
        } catch (_: Exception) {
            null
        }
    }
}
