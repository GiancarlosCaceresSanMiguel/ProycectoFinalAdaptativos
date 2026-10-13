package com.energiacontexto.server

import android.content.Context
import com.energiacontexto.ml.Predictor
import com.energiacontexto.ml.TrainInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalDatabase(context: Context) {
    private val file = File(context.filesDir, "datos.json")
    val sessions = mutableMapOf<String, SessionInfo>()
    val records = mutableListOf<TelemetryRecord>()
    val predictions = mutableListOf<PredictionLog>()
    val policies = mutableListOf<PolicyLog>()
    val log = mutableListOf<LogEntry>()
    val counters = mutableMapOf<String, Int>()
    val idx = mutableMapOf<String, RecordGroup>()
    val seen = mutableSetOf<String>()

    var model: Predictor? = null
    var lastTrainCount = 0
    var modelInfo: TrainInfo = TrainInfo(note = "Aún sin entrenar.")

    init {
        load()
        records.forEach { indexRecord(it) }
    }

    fun indexRecord(r: TelemetryRecord): Boolean {
        val g = idx.getOrPut(r.sessionId) { RecordGroup(mutableListOf(), mutableMapOf()) }
        if (g.byT.containsKey(r.t)) return false
        g.list.add(r)
        g.byT[r.t] = r
        seen.add(r.id)
        return true
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONObject("sessions")?.let { o ->
                o.keys().forEach { id ->
                    val s = o.getJSONObject(id)
                    sessions[id] = SessionInfo(
                        id = id,
                        mode = s.getString("mode"),
                        profile = s.getString("profile"),
                        startedAt = s.getLong("startedAt"),
                        delaySum = s.optDouble("delaySum", 0.0),
                        delayN = s.optInt("delayN", 0),
                        level = if (s.has("level")) s.getInt("level") else null,
                        levelSince = s.optLong("levelSince", 0),
                        reason = s.optString("reason", null),
                    )
                }
            }
            root.optJSONArray("records")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    records.add(parseRecord(r))
                }
            }
            root.optJSONObject("counters")?.keys()?.forEach { k ->
                counters[k] = root.getJSONObject("counters").getInt(k)
            }
        } catch (_: Exception) { /* empezar vacío */ }
    }

    fun saveNow() {
        try {
            val root = JSONObject()
            val sess = JSONObject()
            sessions.forEach { (id, s) ->
                sess.put(id, JSONObject().apply {
                    put("mode", s.mode)
                    put("profile", s.profile)
                    put("startedAt", s.startedAt)
                    put("delaySum", s.delaySum)
                    put("delayN", s.delayN)
                    s.level?.let { put("level", it) }
                    put("levelSince", s.levelSince)
                    s.reason?.let { put("reason", it) }
                })
            }
            root.put("sessions", sess)
            val recArr = JSONArray()
            records.forEach { recArr.put(recordJson(it)) }
            root.put("records", recArr)
            val cnt = JSONObject()
            counters.forEach { (k, v) -> cnt.put(k, v) }
            root.put("counters", cnt)
            val tmp = File(file.parent, "datos.json.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            android.util.Log.e("LocalDatabase", "No se pudo guardar: ${e.message}")
        }
    }

    fun reset() {
        sessions.clear()
        records.clear()
        predictions.clear()
        policies.clear()
        log.clear()
        counters.clear()
        idx.clear()
        seen.clear()
        model = null
        lastTrainCount = 0
        modelInfo = TrainInfo(note = "Aún sin entrenar.")
        if (file.exists()) file.delete()
    }

    companion object {
        fun parseRecord(r: JSONObject) = TelemetryRecord(
            id = r.getString("id"),
            sessionId = r.getString("sessionId"),
            t = r.getLong("t"),
            battery = r.getDouble("battery"),
            charging = r.getBoolean("charging"),
            speed = r.getDouble("speed"),
            fixes = r.getInt("fixes"),
            sends = r.getInt("sends"),
            gps = r.getInt("gps"),
            batch = r.getInt("batch"),
            near = r.getBoolean("near"),
        )

        fun recordJson(r: TelemetryRecord) = JSONObject().apply {
            put("id", r.id)
            put("sessionId", r.sessionId)
            put("t", r.t)
            put("battery", r.battery)
            put("charging", r.charging)
            put("speed", r.speed)
            put("fixes", r.fixes)
            put("sends", r.sends)
            put("gps", r.gps)
            put("batch", r.batch)
            put("near", r.near)
        }
    }
}
