package com.energiacontexto.client

import com.energiacontexto.core.PolicyContext
import com.energiacontexto.core.PolicyEngine
import com.energiacontexto.core.SharedConstants
import com.energiacontexto.core.SharedConstants.LEVELS
import com.energiacontexto.server.BackendServices
import com.energiacontexto.server.SessionPrediction
import com.energiacontexto.server.SyncResponse
import com.energiacontexto.sync.RemoteSync
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

data class BatteryPoint(val t: Long, val battery: Double)

data class SessionUiState(
    val running: Boolean = false,
    val sim: Boolean = false,
    val battery: Double = 0.0,
    val charging: Boolean = false,
    val pred: SessionPrediction? = null,
    val predModelLabel: String? = null,
    val level: Int = 1,
    val mode: String = "adaptive",
    val speed: Double = 0.0,
    val gpsSec: Int = 30,
    val batchSec: Int = 120,
    val queueSize: Int = 0,
    val sessionMin: Int = 0,
    val reason: String = "Inicie una sesión para ver las decisiones del sistema.",
    val history: List<BatteryPoint> = emptyList(),
    val serverOk: Boolean = true,
    val online: Boolean = true,
    val geoError: Boolean = false,
    val note: String = "",
)

class SessionEngine(
    private val backend: BackendServices,
    private val remote: RemoteSync,
    private val prefs: AppPreferences,
) {
    private var run: RunState? = null
    private val queue = mutableListOf<JSONObject>()
    private val flushMutex = Mutex()
    private var flushing = false

    val acts = listOf("reposo", "caminata", "desplazamiento")

    data class RunState(
        val id: String,
        val sim: Boolean,
        var seq: Int,
        val meta: SessionMeta,
        val startVt: Long,
        var vt: Long,
        var acc: Double,
        var lastReal: Long,
        var nextFix: Long,
        var nextSend: Long,
        var nextMin: Long,
        var level: Int,
        var gps: Int,
        var batch: Int,
        val counters: Counters,
        var battery: Double,
        var charging: Boolean,
        var speed: Double,
        var near: Boolean,
        var lastPos: PosSnapshot?,
        val history: MutableList<BatteryPoint>,
        var pred: SessionPrediction?,
        var modelName: String?,
        var reason: String,
        var serverOk: Boolean,
        var bg: Double,
        var autoAct: String,
        var exhausted: Boolean,
        var geoError: Boolean,
        var fixPending: Boolean = false,
    )

    data class SessionMeta(val sessionId: String, val mode: String, val profile: String)
    data class Counters(var fixes: Int, var sends: Int, var speedSum: Double, var speedN: Int)
    data class PosSnapshot(val lat: Double, val lon: Double, val t: Long, val acc: Float)

    data class SimControls(
        val act: String = "auto",
        val offline: Boolean = false,
        val charging: Boolean = false,
        val near: Boolean = false,
    )

    var simControls = SimControls()

    fun isRunning() = run != null

    fun start(cfg: SessionConfig, onUpdate: (SessionUiState) -> Unit): String? {
        if (run != null) return null
        val sim = cfg.source == "sim"
        val base = SharedConstants.PROFILES[cfg.profile]!!.base
        val now = System.currentTimeMillis()
        val id = "s$now"
        run = RunState(
            id = id,
            sim = sim,
            seq = 0,
            meta = SessionMeta(id, cfg.mode, cfg.profile),
            startVt = now,
            vt = now,
            acc = 0.0,
            lastReal = now,
            nextFix = now,
            nextSend = now + LEVELS[base].batch * 1000L,
            nextMin = now + 60_000L,
            level = base,
            gps = LEVELS[base].gps,
            batch = LEVELS[base].batch,
            counters = Counters(0, 0, 0.0, 0),
            battery = if (sim) cfg.simStart else cfg.realBatteryPct,
            charging = if (sim) false else cfg.realCharging,
            speed = 0.0,
            near = false,
            lastPos = null,
            history = mutableListOf(),
            pred = null,
            modelName = null,
            reason = "Sesión iniciada con el nivel base del perfil.",
            serverOk = true,
            bg = 0.0,
            autoAct = "reposo",
            exhausted = false,
            geoError = false,
        )
        queue.clear()
        val note = if (sim) "" else "La sesión continúa en segundo plano con el servicio en primer plano."
        emitUi(onUpdate, note)
        return note
    }

    suspend fun stop(onUpdate: (SessionUiState) -> Unit, msg: String? = null) {
        val r = run ?: return
        run = null
        flushMutex.withLock {
            if (remote.isOnline() && !r.sim) {
                queue.forEach { e -> if (!e.has("sentAt")) e.put("sentAt", r.vt) }
            }
            flushInternal(r, onUpdate)
        }
        prefs.savePending(if (queue.isNotEmpty()) PendingSession(r.meta, queue.toList()) else null)
        queue.clear()
        emitUi(onUpdate, msg ?: "Sesión detenida. Consulte los resultados en el panel.")
    }

    suspend fun tick(now: Long, cfg: SessionConfig, onUpdate: (SessionUiState) -> Unit) {
        val r = run ?: return
        r.acc += (now - r.lastReal) / 1000.0 * (if (r.sim) cfg.simSpeed.toDouble() else 1.0)
        r.lastReal = now
        var steps = 0
        while (r.acc >= 1 && steps < 3000) {
            step(r, cfg)
            r.acc -= 1
            steps++
        }
        if (r.acc >= 1) r.acc = 0.0
        if (r.exhausted) {
            stop(onUpdate, "La batería simulada se agotó. La sesión terminó.")
            return
        }
        flushMutex.withLock { flushInternal(r, onUpdate) }
        emitUi(onUpdate, null)
    }

    fun onLocation(lat: Double, lon: Double, speedMps: Float?, accuracy: Float, t: Long) {
        val r = run ?: return
        if (r.sim) return
        r.counters.fixes++
        var sp = 0.0
        if (speedMps != null && speedMps >= 0) sp = speedMps.toDouble()
        else {
            val last = r.lastPos
            if (last != null) {
                val d = GeoUtils.dist(LatLon(last.lat, last.lon), LatLon(lat, lon))
                val dt = (t - last.t) / 1000.0
                if (dt > 0 && d > max(20.0, accuracy.toDouble())) sp = d / dt
            }
        }
        r.lastPos = PosSnapshot(lat, lon, t, accuracy)
        val zones = prefs.loadZones()
        r.near = zones.any { GeoUtils.dist(it, LatLon(lat, lon)) < 150 }
        r.geoError = false
        addFix(r, sp)
        r.fixPending = false
        r.nextFix = r.vt + r.gps * 1000L
    }

    fun onGeoError() {
        run?.geoError = true
    }

    fun updateRealBattery(pct: Double, charging: Boolean) {
        run?.let {
            if (!it.sim) {
                it.battery = pct
                it.charging = charging
            }
        }
    }

    private fun step(r: RunState, cfg: SessionConfig) {
        r.vt += 1000
        if (r.vt >= r.nextFix) {
            if (r.sim) {
                r.counters.fixes++
                addFix(r, simSpeed())
                r.nextFix = r.vt + r.gps * 1000L
            } else if (!r.fixPending) {
                r.fixPending = true
            }
        }
        if (r.vt >= r.nextSend) {
            doSend(r)
            r.nextSend = r.vt + r.batch * 1000L
        }
        if (r.vt >= r.nextMin) {
            doMinute(r, cfg)
            r.nextMin += 60_000L
        }
    }

    private fun currentAct(): String {
        val a = simControls.act
        val r = run!!
        return if (a == "auto") r.autoAct else a
    }

    private fun simSpeed(): Double {
        return when (currentAct()) {
            "reposo" -> 0.0
            "caminata" -> max(0.6, 1.4 + (Random.nextDouble() - 0.5) * 0.6)
            else -> 10 + (Random.nextDouble() - 0.5) * 4
        }
    }

    private fun addFix(r: RunState, speed: Double) {
        r.speed = speed
        r.counters.speedSum += speed
        r.counters.speedN++
        queue.add(
            JSONObject()
                .put("id", newId(r))
                .put("type", "PosicionActualizada")
                .put("t", r.vt)
                .put("speed", round2(speed)),
        )
    }

    private fun doSend(r: RunState) {
        if (!isOnline(r)) return
        var n = 0
        for (e in queue) {
            if (!e.has("sentAt")) {
                e.put("sentAt", r.vt)
                n++
            }
        }
        if (n > 0) r.counters.sends++
    }

    private fun simBattery(r: RunState) {
        val c = r.counters
        if (Random.nextDouble() < 0.03) r.bg = if (r.bg > 0) 0.0 else 0.08
        if (r.charging) {
            r.battery = min(100.0, r.battery + 0.5)
            return
        }
        val actCost = when (currentAct()) {
            "reposo" -> 0.0
            "caminata" -> 0.03
            else -> 0.06
        }
        val fixCost = if (LEVELS[r.level].alta) 0.012 else 0.008
        val cost = 0.06 + c.fixes * fixCost + c.sends * 0.02 + actCost + r.bg
        r.battery = max(0.0, r.battery - cost * (0.85 + Random.nextDouble() * 0.3))
    }

    private fun doMinute(r: RunState, cfg: SessionConfig) {
        val c = r.counters
        if (r.sim) {
            if (Random.nextDouble() < 0.1) r.autoAct = acts[Random.nextInt(acts.size)]
            r.charging = simControls.charging
            r.near = simControls.near
            simBattery(r)
        }
        val speed = if (c.speedN > 0) c.speedSum / c.speedN else r.speed
        r.speed = speed
        queue.add(
            JSONObject()
                .put("id", newId(r))
                .put("type", "TelemetriaRegistrada")
                .put("t", r.vt)
                .put("battery", round2(r.battery))
                .put("charging", r.charging)
                .put("speed", round2(speed))
                .put("fixes", c.fixes)
                .put("sends", c.sends)
                .put("gps", r.gps)
                .put("batch", r.batch)
                .put("near", r.near)
                .put("online", isOnline(r)),
        )
        r.history.add(BatteryPoint(r.vt, r.battery))
        if (r.history.size > 720) r.history.removeAt(0)
        r.counters = Counters(0, 0, 0.0, 0)
        if (!isOnline(r) || !r.serverOk) localAdapt(r)
        if (r.sim && !r.charging && r.battery <= 0.5) r.exhausted = true
    }

    private fun setLevel(r: RunState, level: Int, reason: String) {
        r.reason = reason
        if (level == r.level) return
        r.level = level
        r.gps = LEVELS[level].gps
        r.batch = LEVELS[level].batch
        r.nextFix = min(r.nextFix, r.vt + r.gps * 1000L)
        r.nextSend = min(r.nextSend, r.vt + r.batch * 1000L)
        queue.add(
            JSONObject()
                .put("id", newId(r))
                .put("type", "PoliticaAplicada")
                .put("t", r.vt)
                .put("level", level),
        )
    }

    private fun localBaseline(r: RunState): Double? {
        val h = r.history
        if (h.size < 2) return null
        val k = min(10, h.size - 1)
        return max(0.0, (h[h.size - 1 - k].battery - h.last().battery) / k * 30)
    }

    private fun localAdapt(r: RunState) {
        val drop = localBaseline(r)
        val choice = PolicyEngine.choosePolicy(
            PolicyContext(
                profile = r.meta.profile,
                mode = r.meta.mode,
                battery = r.battery,
                predictedDrop = drop,
                speed = r.speed,
                nearZone = r.near,
                charging = r.charging,
            ),
        )
        r.pred = drop?.let { SessionPrediction(it, "regla local", r.vt) }
        setLevel(r, choice.level, "(Regla local) ${choice.reason}")
    }

    private suspend fun flushInternal(r: RunState, onUpdate: (SessionUiState) -> Unit) {
        if (flushing) return
        val chunk = queue.filter { it.has("sentAt") }.take(2000)
        if (chunk.isEmpty()) return
        flushing = true
        try {
            val data: SyncResponse = if (remote.useRemote()) {
                remote.postSync(r.meta.sessionId, r.meta.mode, r.meta.profile, chunk)
            } else {
                backend.syncLocal(r.meta.sessionId, r.meta.mode, r.meta.profile, chunk)
            }
            val ids = chunk.map { it.getString("id") }.toSet()
            queue.removeAll { ids.contains(it.optString("id")) }
            r.serverOk = true
            applyServer(r, data)
        } catch (_: Exception) {
            chunk.forEach { it.remove("sentAt") }
            r.serverOk = false
        } finally {
            flushing = false
        }
    }

    private fun applyServer(r: RunState, data: SyncResponse) {
        r.pred = data.prediction
        r.modelName = data.model.chosen
        data.policy?.let { setLevel(r, it.level, it.reason) }
    }

    suspend fun recoverPending() {
        val p = prefs.loadPending() ?: return
        p.events.forEach { it.put("sentAt", System.currentTimeMillis()) }
        try {
            if (remote.useRemote()) {
                remote.postSync(p.meta.sessionId, p.meta.mode, p.meta.profile, p.events)
            } else {
                backend.syncLocal(p.meta.sessionId, p.meta.mode, p.meta.profile, p.events)
            }
            prefs.savePending(null)
        } catch (_: Exception) { }
    }

    fun needsFix(): Boolean = run?.let { !it.sim && it.fixPending } == true

    fun currentLevel(): Int = run?.level ?: 1

    fun lastPosition(): LatLon? = run?.lastPos?.let { LatLon(it.lat, it.lon) }

    fun uiState(noteOverride: String? = null): SessionUiState {
        val r = run
        if (r == null) {
            return SessionUiState(
                running = false,
                note = noteOverride ?: "",
            )
        }
        val online = isOnline(r)
        return SessionUiState(
            running = true,
            sim = r.sim,
            battery = r.battery,
            charging = r.charging,
            pred = r.pred,
            predModelLabel = r.modelName?.let { SharedConstants.MODEL_NAMES[it] ?: it },
            level = r.level,
            mode = r.meta.mode,
            speed = r.speed,
            gpsSec = r.gps,
            batchSec = r.batch,
            queueSize = queue.size,
            sessionMin = ((r.vt - r.startVt) / 60_000).toInt(),
            reason = r.reason,
            history = r.history.toList(),
            serverOk = r.serverOk,
            online = online,
            geoError = r.geoError,
            note = noteOverride ?: "",
        )
    }

    private fun emitUi(onUpdate: (SessionUiState) -> Unit, note: String?) {
        onUpdate(uiState(note))
    }

    private fun isOnline(r: RunState) = if (r.sim && simControls.offline) false else remote.isOnline()

    private fun newId(r: RunState) = "${r.id}:${++r.seq}"

    private fun round2(v: Double) = round(v * 100) / 100
}

data class SessionConfig(
    val profile: String,
    val mode: String,
    val source: String,
    val simSpeed: Int,
    val simStart: Double,
    val realBatteryPct: Double = 100.0,
    val realCharging: Boolean = false,
)

data class PendingSession(val meta: SessionEngine.SessionMeta, val events: List<JSONObject>)
