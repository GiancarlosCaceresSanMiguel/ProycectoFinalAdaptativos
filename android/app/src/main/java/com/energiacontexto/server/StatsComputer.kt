package com.energiacontexto.server

import com.energiacontexto.core.SharedConstants
import com.energiacontexto.core.SharedConstants.MIN_MS
import com.energiacontexto.core.SharedConstants.QUALITY_M
import org.json.JSONObject
import kotlin.math.abs

object StatsComputer {
    data class ModeStats(
        val minutes: Int,
        val drainPerHour: Double?,
        val fixesPerHour: Double?,
        val sendsPerHour: Double?,
        val delaySec: Double?,
        val quality: Double?,
    )

    fun compute(db: LocalDatabase, profileFilter: String = ""): JSONObject {
        data class Acc(var min: Int = 0, var drain: Double = 0.0, var fixes: Int = 0, var sends: Int = 0, var ok: Int = 0, var delaySum: Double = 0.0, var delayN: Int = 0)
        val modes = mapOf("fixed" to Acc(), "adaptive" to Acc())
        val acts = mutableMapOf<String, MutableMap<String, Pair<Int, Double>>>()
        var sessions = 0

        for ((id, g) in db.idx) {
            val ses = db.sessions[id] ?: continue
            if (profileFilter.isNotEmpty() && ses.profile != profileFilter) continue
            val a = modes[ses.mode] ?: continue
            sessions++
            a.delaySum += ses.delaySum
            a.delayN += ses.delayN
            for (b in g.list) {
                val prev = g.byT[b.t - MIN_MS] ?: continue
                if (b.charging || prev.charging) continue
                val drain = prev.battery - b.battery
                a.min++
                a.drain += drain
                a.fixes += b.fixes
                a.sends += b.sends
                if (b.speed * b.gps <= QUALITY_M) a.ok++
                val act = SharedConstants.activityOf(b.speed)
                val actMap = acts.getOrPut(act) {
                    mutableMapOf("fixed" to (0 to 0.0), "adaptive" to (0 to 0.0))
                }
                val cur = actMap[ses.mode]!!
                actMap[ses.mode] = cur.first + 1 to cur.second + drain
            }
        }

        fun perHour(v: Double, m: Int) = if (m > 0) v / m * 60 else null
        val outModes = JSONObject()
        for (k in listOf("fixed", "adaptive")) {
            val a = modes[k]!!
            outModes.put(k, JSONObject().apply {
                put("minutes", a.min)
                put("drainPerHour", perHour(a.drain, a.min))
                put("fixesPerHour", perHour(a.fixes.toDouble(), a.min))
                put("sendsPerHour", perHour(a.sends.toDouble(), a.min))
                put("delaySec", if (a.delayN > 0) a.delaySum / a.delayN else JSONObject.NULL)
                put("quality", if (a.min > 0) a.ok.toDouble() / a.min * 100 else JSONObject.NULL)
            })
        }

        val activities = JSONObject()
        for (act in listOf("reposo", "caminata", "desplazamiento")) {
            val x = acts[act] ?: mapOf("fixed" to (0 to 0.0), "adaptive" to (0 to 0.0))
            activities.put(act, JSONObject().apply {
                for (k in listOf("fixed", "adaptive")) {
                    val (m, d) = x[k] ?: (0 to 0.0)
                    put(k, JSONObject().apply {
                        put("minutes", m)
                        put("rate", perHour(d, m))
                    })
                }
            })
        }

        var n = 0
        var e1 = 0.0
        var e2 = 0.0
        for (p in db.predictions) {
            val g = db.idx[p.sessionId] ?: continue
            val fut = g.byT[p.t + SharedConstants.HORIZON_MIN * MIN_MS] ?: continue
            if (fut.charging) continue
            val actual = p.battery - fut.battery
            n++
            e1 += abs(p.predicted - actual)
            e2 += abs(p.baseline - actual)
        }

        val totalEvents = db.counters.values.sum()
        return JSONObject().apply {
            put("counters", JSONObject(db.counters.mapValues { it.value }))
            put("totalEvents", totalEvents)
            put("sessions", sessions)
            put("records", db.records.size)
            put("model", JSONObject().apply {
                put("chosen", db.modelInfo.chosen)
                put("samples", db.modelInfo.samples)
                put("note", db.modelInfo.note)
                put("mae", JSONObject().apply {
                    db.modelInfo.mae.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
                })
            })
            put("live", JSONObject().apply {
                put("n", n)
                put("mae", if (n > 0) e1 / n else JSONObject.NULL)
                put("maeBase", if (n > 0) e2 / n else JSONObject.NULL)
            })
            put("modes", outModes)
            put("activities", activities)
            put("policies", JSONArrayPolicies(db.policies.takeLast(15).reversed()))
            put("log", JSONArrayLog(db.log.takeLast(25).reversed()))
        }
    }

    private fun JSONArrayPolicies(list: List<PolicyLog>) = org.json.JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("t", p.t)
                put("level", p.level)
                put("reason", p.reason)
            })
        }
    }

    private fun JSONArrayLog(list: List<LogEntry>) = org.json.JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("t", e.t)
                put("name", e.name)
                put("info", e.info)
            })
        }
    }
}
