package com.energiacontexto.ml

import com.energiacontexto.core.SharedConstants.HORIZON_MIN
import com.energiacontexto.core.SharedConstants.MIN_MS
import com.energiacontexto.server.RecordGroup
import com.energiacontexto.server.TelemetryRecord
import kotlin.math.abs
import kotlin.math.sqrt

private const val MIN_SAMPLES = 150

interface Predictor {
    fun predict(x: DoubleArray): Double
}

data class Sample(val x: DoubleArray, val y: Double, val base: Double)

data class TrainInfo(
    var samples: Int = 0,
    var chosen: String = "baseline",
    val mae: MutableMap<String, Double?> = mutableMapOf(),
    var trainedAt: Long = System.currentTimeMillis(),
    var note: String = "",
)

data class TrainResult(val model: Predictor?, val info: TrainInfo)

object BatteryModel {
    private fun rate(rec: TelemetryRecord, byT: Map<Long, TelemetryRecord>, k: Int): Double? {
        for (j in k downTo 1) {
            val prev = byT[rec.t - j * MIN_MS] ?: continue
            if (prev.charging) return null
            return (prev.battery - rec.battery) / j
        }
        return null
    }

    fun features(rec: TelemetryRecord, byT: Map<Long, TelemetryRecord>): DoubleArray? {
        val r5 = rate(rec, byT, 5) ?: return null
        val r15 = rate(rec, byT, 15) ?: return null
        return doubleArrayOf(rec.battery, r5, r15, rec.speed, rec.fixes.toDouble(), rec.sends.toDouble())
    }

    fun baseline(rec: TelemetryRecord, byT: Map<Long, TelemetryRecord>): Double? {
        val r = rate(rec, byT, 10) ?: return null
        return maxOf(0.0, r * HORIZON_MIN)
    }

    fun buildDataset(groups: List<RecordGroup>): List<Sample> {
        val out = mutableListOf<Sample>()
        for (g in groups) {
            for (rec in g.list) {
                if (rec.charging) continue
                val fut = g.byT[rec.t + HORIZON_MIN * MIN_MS] ?: continue
                if (fut.charging) continue
                var ok = true
                for (m in 1 until HORIZON_MIN) {
                    val mid = g.byT[rec.t + m * MIN_MS]
                    if (mid == null || mid.charging) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue
                val x = features(rec, g.byT) ?: continue
                val base = baseline(rec, g.byT) ?: continue
                out.add(Sample(x, rec.battery - fut.battery, base))
            }
        }
        return out
    }

    private fun fitLinear(X: List<DoubleArray>, y: List<Double>, lambda: Double = 1.0): Predictor {
        val n = X.size
        val d = X[0].size
        val k = d + 1
        val mean = DoubleArray(d)
        val std = DoubleArray(d)
        for (r in X) for (j in 0 until d) mean[j] += r[j] / n
        for (r in X) for (j in 0 until d) std[j] += (r[j] - mean[j]).let { it * it } / n
        for (j in 0 until d) std[j] = sqrt(std[j]).let { if (it == 0.0) 1.0 else it }

        val A = Array(k) { DoubleArray(k + 1) }
        for (r in 0 until n) {
            val z = DoubleArray(k) { i ->
                if (i == 0) 1.0 else (X[r][i - 1] - mean[i - 1]) / std[i - 1]
            }
            for (i in 0 until k) {
                for (j in 0 until k) A[i][j] += z[i] * z[j]
                A[i][k] += z[i] * y[r]
            }
        }
        for (i in 1 until k) A[i][i] += lambda
        val w = solve(A)
        return object : Predictor {
            override fun predict(x: DoubleArray): Double {
                var s = w[0]
                for (j in 0 until d) s += w[j + 1] * (x[j] - mean[j]) / std[j]
                return s
            }
        }
    }

    private fun solve(A: Array<DoubleArray>): DoubleArray {
        val k = A.size
        val m = A.map { it.copyOf() }.toTypedArray()
        for (c in 0 until k) {
            var p = c
            for (r in c + 1 until k) {
                if (abs(m[r][c]) > abs(m[p][c])) p = r
            }
            m[c] = m[p].also { m[p] = m[c] }
            val div = m[c][c].let { if (it == 0.0) 1e-9 else it }
            for (j in c..k) m[c][j] /= div
            for (r in 0 until k) {
                if (r == c) continue
                val f = m[r][c]
                for (j in c..k) m[r][j] -= f * m[c][j]
            }
        }
        return DoubleArray(k) { m[it][k] }
    }

    private sealed class TreeNode {
        data class Leaf(val v: Double) : TreeNode()
        data class Split(val f: Int, val thr: Double, val l: TreeNode, val r: TreeNode) : TreeNode()
    }

    private fun fitTree(X: List<DoubleArray>, y: List<Double>, maxDepth: Int = 3, minLeaf: Int = 10): Predictor {
        fun build(idx: List<Int>, depth: Int): TreeNode {
            val mean = idx.sumOf { y[it] } / idx.size
            if (depth == 0 || idx.size < 2 * minLeaf) return TreeNode.Leaf(mean)
            var best: Triple<Double, Int, Double>? = null
            for (f in X[0].indices) {
                val vals = idx.map { X[it][f] }.sorted()
                for (q in 1 until 10) {
                    val thr = vals[vals.size * q / 10]
                    var nl = 0; var sl = 0.0; var ql = 0.0
                    var nr = 0; var sr = 0.0; var qr = 0.0
                    for (i in idx) {
                        if (X[i][f] <= thr) {
                            nl++; sl += y[i]; ql += y[i] * y[i]
                        } else {
                            nr++; sr += y[i]; qr += y[i] * y[i]
                        }
                    }
                    if (nl < minLeaf || nr < minLeaf) continue
                    val sse = (ql - sl * sl / nl) + (qr - sr * sr / nr)
                    if (best == null || sse < best!!.first) best = Triple(sse, f, thr)
                }
            }
            if (best == null) return TreeNode.Leaf(mean)
            val (_, f, thr) = best
            return TreeNode.Split(
                f, thr,
                build(idx.filter { X[it][f] <= thr }, depth - 1),
                build(idx.filter { X[it][f] > thr }, depth - 1),
            )
        }
        val root = build(X.indices.toList(), maxDepth)
        return object : Predictor {
            override fun predict(x: DoubleArray): Double {
                var n: TreeNode = root
                while (n is TreeNode.Split) {
                    n = if (x[n.f] <= n.thr) n.l else n.r
                }
                return (n as TreeNode.Leaf).v
            }
        }
    }

    private fun mae(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.map { abs(it) }.average()

    fun train(samples: List<Sample>): TrainResult {
        val info = TrainInfo(samples = samples.size, note = "")
        val cut = (samples.size * 0.7).toInt()
        val tr = samples.subList(0, cut)
        val te = samples.subList(cut, samples.size)
        info.mae["baseline"] = mae(te.map { it.base - it.y })
        if (samples.size < MIN_SAMPLES) {
            info.note =
                "Se necesitan al menos $MIN_SAMPLES ejemplos (30 min de datos continuos cada uno). Mientras tanto se usa la referencia."
            return TrainResult(null, info)
        }
        val Xtr = tr.map { it.x }
        val ytr = tr.map { it.y }
        val makers = mapOf<String, (List<DoubleArray>, List<Double>) -> Predictor>(
            "linear" to { x, y -> fitLinear(x, y) },
            "tree" to { x, y -> fitTree(x, y) },
        )
        var best = "baseline"
        var bestMae = (info.mae["baseline"] ?: Double.MAX_VALUE) * 0.95
        for ((name, maker) in makers) {
            val m = maker(Xtr, ytr)
            info.mae[name] = mae(te.map { s -> m.predict(s.x) - s.y })
            val err = info.mae[name]!!
            if (err < bestMae) {
                best = name
                bestMae = err
            }
        }
        info.chosen = best
        info.note =
            "Entrenado con ${tr.size} ejemplos y evaluado con los ${te.size} más recientes. Un modelo solo se usa si mejora a la referencia al menos un 5 %."
        val model = if (best == "baseline") null
        else makers[best]!!(samples.map { it.x }, samples.map { it.y })
        return TrainResult(model, info)
    }
}
