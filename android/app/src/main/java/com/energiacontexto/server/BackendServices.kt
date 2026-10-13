package com.energiacontexto.server

import com.energiacontexto.core.PolicyContext
import com.energiacontexto.core.PolicyEngine
import com.energiacontexto.core.SharedConstants
import com.energiacontexto.core.SharedConstants.MIN_DWELL_MIN
import com.energiacontexto.core.SharedConstants.MIN_MS
import com.energiacontexto.ml.BatteryModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class SyncResponse(
    val accepted: Int,
    val policy: PolicyChoiceDto?,
    val prediction: SessionPrediction?,
    val model: ModelSummary,
)

data class PolicyChoiceDto(val level: Int, val reason: String)
data class ModelSummary(val chosen: String, val samples: Int)

class BackendServices(private val db: LocalDatabase) {
    private val bus = EventBus()
    private var saveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        wireServices()
        retrain()
    }

    private fun publish(name: String, data: Any, info: String = "") {
        db.counters[name] = (db.counters[name] ?: 0) + 1
        val notLogged = name == "PosicionActualizada" || name == "TelemetriaValidada"
        if (!notLogged) {
            db.log.add(LogEntry(name, System.currentTimeMillis(), (data as? JSONObject)?.optString("sessionId"), info))
            if (db.log.size > 100) db.log.removeAt(0)
        }
        bus.emit(name, data)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            db.saveNow()
        }
    }

    private fun wireServices() {
        bus.on("TelemetriaRegistrada") { raw ->
            val e = raw as JSONObject
            val ok = listOf("t", "battery", "speed", "fixes", "sends", "gps", "batch").all {
                e.has(it) && !e.isNull(it)
            } && e.getDouble("battery") in 0.0..100.0 &&
                e.getInt("fixes") >= 0 && e.getInt("sends") >= 0
            if (!ok) return@on
            val rec = TelemetryRecord(
                id = e.getString("id"),
                sessionId = e.getString("sessionId"),
                t = e.getLong("t"),
                battery = e.getDouble("battery"),
                charging = e.optBoolean("charging", false),
                speed = max(0.0, e.getDouble("speed")),
                fixes = e.getInt("fixes"),
                sends = e.getInt("sends"),
                gps = e.getInt("gps"),
                batch = e.getInt("batch"),
                near = e.optBoolean("near", false),
            )
            if (!db.indexRecord(rec)) return@on
            db.records.add(rec)
            publish("TelemetriaValidada", mapOf("sessionId" to rec.sessionId, "record" to rec))
        }

        bus.on("TelemetriaValidada") { raw ->
            @Suppress("UNCHECKED_CAST")
            val data = raw as Map<String, Any>
            val sessionId = data["sessionId"] as String
            val record = data["record"] as TelemetryRecord
            var pred: PredictionResult? = null
            if (!record.charging) {
                try {
                    pred = predictFor(record, db.idx[sessionId]!!.byT)
                } catch (e: Exception) {
                    db.model = null
                }
            }
            if (pred != null) {
                db.predictions.add(
                    PredictionLog(sessionId, record.t, record.battery, pred.drop, pred.baseline, pred.model),
                )
                if (db.predictions.size > 5000) db.predictions.removeAt(0)
            }
            publish(
                "ConsumoPredicho",
                mapOf("sessionId" to sessionId, "record" to record, "prediction" to pred),
                if (pred != null) "bajada prevista ${"%.1f".format(pred.drop)}% (${pred.model})" else "sin predicción",
            )
            if (db.records.size - db.lastTrainCount >= 120) retrain()
        }

        bus.on("ConsumoPredicho") { raw ->
            @Suppress("UNCHECKED_CAST")
            val data = raw as Map<String, Any?>
            val sessionId = data["sessionId"] as String
            val record = data["record"] as TelemetryRecord
            val prediction = data["prediction"] as PredictionResult?
            val ses = db.sessions[sessionId] ?: return@on
            val choice = PolicyEngine.choosePolicy(
                PolicyContext(
                    profile = ses.profile,
                    mode = ses.mode,
                    battery = record.battery,
                    predictedDrop = prediction?.drop,
                    speed = record.speed,
                    nearZone = record.near,
                    charging = record.charging,
                ),
            )
            ses.prediction = prediction?.let { SessionPrediction(it.drop, it.model, record.t) }
            val first = ses.level == null
            val dwellOk = first || record.t - ses.levelSince >= MIN_DWELL_MIN * MIN_MS
            if (first || (choice.level != ses.level && dwellOk)) {
                ses.level = choice.level
                ses.levelSince = record.t
                ses.reason = choice.reason
                db.policies.add(PolicyLog(sessionId, System.currentTimeMillis(), choice.level, choice.reason))
                if (db.policies.size > 200) db.policies.removeAt(0)
                publish("PoliticaSeleccionada", mapOf("sessionId" to sessionId, "level" to choice.level),
                    "frecuencia ${SharedConstants.LEVELS[choice.level].nombre.lowercase()}")
            } else if (choice.level == ses.level) {
                ses.reason = choice.reason
            }
        }
    }

    data class PredictionResult(val drop: Double, val baseline: Double, val model: String)

    private fun predictFor(rec: TelemetryRecord, byT: Map<Long, TelemetryRecord>): PredictionResult? {
        val base = BatteryModel.baseline(rec, byT) ?: return null
        var drop = base
        var model = "baseline"
        val x = BatteryModel.features(rec, byT)
        if (db.model != null && x != null) {
            drop = db.model!!.predict(x)
            model = db.modelInfo.chosen
        }
        return PredictionResult(
            drop = min(max(drop, 0.0), rec.battery),
            baseline = base,
            model = model,
        )
    }

    fun retrain(): com.energiacontexto.ml.TrainInfo {
        val groups = db.idx.entries
            .map { (id, g) ->
                RecordGroup(
                    g.list.sortedBy { it.t }.toMutableList(),
                    g.byT,
                ).let { rg ->
                    Pair(db.sessions[id]?.startedAt ?: 0L, rg)
                }
            }
            .sortedBy { it.first }
            .map { it.second }
        val r = BatteryModel.train(BatteryModel.buildDataset(groups))
        db.model = r.model
        db.modelInfo = r.info
        db.lastTrainCount = db.records.size
        return r.info
    }

    fun handleSync(body: JSONObject): SyncResponse {
        val sessionId = body.getString("sessionId")
        val mode = body.getString("mode")
        val profile = body.getString("profile")
        require(mode in listOf("adaptive", "fixed"))
        require(profile in SharedConstants.PROFILES.keys)
        val events = body.getJSONArray("events")

        val ses = db.sessions.getOrPut(sessionId) {
            SessionInfo(sessionId, mode, profile, System.currentTimeMillis())
        }
        ses.mode = mode
        ses.profile = profile
        if (db.seen.size > 500_000) db.seen.clear()

        var n = 0
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val id = e.optString("id", "")
            if (id.isEmpty() || db.seen.contains(id)) continue
            db.seen.add(id)
            n++
            when (e.getString("type")) {
                "PosicionActualizada" -> {
                    if (e.has("sentAt") && e.has("t")) {
                        ses.delaySum += max(0.0, (e.getLong("sentAt") - e.getLong("t")) / 1000.0)
                        ses.delayN++
                    }
                    publish("PosicionActualizada", JSONObject().put("sessionId", sessionId))
                }
                "TelemetriaRegistrada" -> {
                    val ev = JSONObject(e.toString()).put("sessionId", sessionId)
                    publish("TelemetriaRegistrada", ev, "batería ${e.getDouble("battery")}%")
                }
                "PoliticaAplicada" -> {
                    val lvl = e.getInt("level")
                    publish("PoliticaAplicada", JSONObject().put("sessionId", sessionId),
                        "frecuencia ${SharedConstants.LEVELS.getOrNull(lvl)?.nombre?.lowercase() ?: "?"}")
                }
            }
        }
        publish("SincronizacionCompletada", JSONObject().put("sessionId", sessionId), "$n eventos nuevos")
        scheduleSave()

        val policy = ses.level?.let { PolicyChoiceDto(it, ses.reason ?: "") }
        return SyncResponse(n, policy, ses.prediction, ModelSummary(db.modelInfo.chosen, db.modelInfo.samples))
    }

    /** Procesa eventos en el dispositivo (modo local, sin HTTP). */
    fun syncLocal(sessionId: String, mode: String, profile: String, events: List<JSONObject>): SyncResponse {
        val arr = JSONArray()
        events.forEach { arr.put(it) }
        return handleSync(
            JSONObject()
                .put("sessionId", sessionId)
                .put("mode", mode)
                .put("profile", profile)
                .put("events", arr),
        )
    }
}
