package com.energiacontexto.server

data class TelemetryRecord(
    val id: String,
    val sessionId: String,
    val t: Long,
    val battery: Double,
    val charging: Boolean,
    val speed: Double,
    val fixes: Int,
    val sends: Int,
    val gps: Int,
    val batch: Int,
    val near: Boolean,
)

data class SessionInfo(
    val id: String,
    var mode: String,
    var profile: String,
    var startedAt: Long,
    var delaySum: Double = 0.0,
    var delayN: Int = 0,
    var level: Int? = null,
    var levelSince: Long = 0,
    var reason: String? = null,
    var prediction: SessionPrediction? = null,
)

data class SessionPrediction(val drop: Double, val model: String, val t: Long)

data class RecordGroup(val list: MutableList<TelemetryRecord>, val byT: MutableMap<Long, TelemetryRecord>)

data class LogEntry(val name: String, val t: Long, val session: String?, val info: String)

data class PolicyLog(val sessionId: String, val t: Long, val level: Int, val reason: String)

data class PredictionLog(
    val sessionId: String,
    val t: Long,
    val battery: Double,
    val predicted: Double,
    val baseline: Double,
    val model: String,
)
