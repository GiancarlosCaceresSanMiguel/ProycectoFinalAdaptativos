package com.energiacontexto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.energiacontexto.core.SharedConstants
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun n1(v: Any?) = when (v) {
    null, JSONObject.NULL -> "–"
    is Number -> "%.1f".format(v.toDouble())
    else -> v.toString()
}

private fun n2(v: Any?) = when (v) {
    null, JSONObject.NULL -> "–"
    is Number -> "%.2f".format(v.toDouble())
    else -> v.toString()
}

@Composable
fun StatsPanel(json: JSONObject, modifier: Modifier = Modifier) {
    val m = json.getJSONObject("model")
    val f = json.getJSONObject("modes").getJSONObject("fixed")
    val a = json.getJSONObject("modes").getJSONObject("adaptive")
    val live = json.getJSONObject("live")
    val activities = json.getJSONObject("activities")
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(modifier) {
        CardBlock {
            MetricGrid(
                listOf(
                    "Eventos" to json.getInt("totalEvents").toString(),
                    "Sesiones" to json.getInt("sessions").toString(),
                    "Registros" to json.getInt("records").toString(),
                    "Modelo" to (SharedConstants.MODEL_NAMES[m.getString("chosen")] ?: m.getString("chosen")),
                ),
            )
        }
        CardBlock {
            Text("Predicción (30 min)", fontWeight = FontWeight.SemiBold)
            SimpleTable(
                listOf("Modelo", "Error medio"),
                listOf(
                    listOf("Referencia", n2(m.getJSONObject("mae").opt("baseline"))),
                    listOf("Regresión lineal", n2(m.getJSONObject("mae").opt("linear"))),
                    listOf("Árbol", n2(m.getJSONObject("mae").opt("tree"))),
                ),
            )
            Text(m.optString("note", ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Text(
                "Comprobadas (${live.getInt("n")}): error ${n2(live.opt("mae"))} vs referencia ${n2(live.opt("maeBase"))}",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        CardBlock {
            Text("Fijo frente a adaptativo", fontWeight = FontWeight.SemiBold)
            SimpleTable(
                listOf("Indicador", "Fijo", "Adaptativo"),
                listOf(
                    listOf("Minutos", f.getInt("minutes").toString(), a.getInt("minutes").toString()),
                    listOf("Consumo %/h", n2(f.opt("drainPerHour")), n2(a.opt("drainPerHour"))),
                    listOf("GPS/h", n1(f.opt("fixesPerHour")), n1(a.opt("fixesPerHour"))),
                    listOf("Envíos/h", n1(f.opt("sendsPerHour")), n1(a.opt("sendsPerHour"))),
                    listOf("Retraso (s)", n1(f.opt("delaySec")), n1(a.opt("delaySec"))),
                    listOf("Calidad %", n1(f.opt("quality")), n1(a.opt("quality"))),
                ),
            )
            Text(
                verdictText(activities, f, a),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        CardBlock {
            Text("Últimas políticas", fontWeight = FontWeight.SemiBold)
            val pol = json.getJSONArray("policies")
            if (pol.length() == 0) {
                Text("Todavía no hay cambios.", style = MaterialTheme.typography.bodySmall)
            } else {
                for (i in 0 until pol.length()) {
                    val p = pol.getJSONObject(i)
                    Text(
                        "${fmt.format(Date(p.getLong("t")))} · ${SharedConstants.LEVELS[p.getInt("level")].nombre}: ${p.getString("reason")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun verdictText(activities: JSONObject, f: JSONObject, a: JSONObject): String {
    var wf = 0.0
    var wa = 0.0
    val keys = listOf("reposo", "caminata", "desplazamiento")
    for (act in keys) {
        if (!activities.has(act)) continue
        val x = activities.getJSONObject(act)
        val fm = x.getJSONObject("fixed")
        val am = x.getJSONObject("adaptive")
        if (fm.getInt("minutes") >= 5 && am.getInt("minutes") >= 5) {
            wf += am.getInt("minutes") * fm.optDouble("rate", 0.0)
            wa += am.getInt("minutes") * am.optDouble("rate", 0.0)
        }
    }
    if (wf <= 0) {
        return "Aún no hay datos suficientes de ambos modos (≥5 min por actividad en cada modo)."
    }
    val saving = (1 - wa / wf) * 100
    val qA = if (a.isNull("quality")) null else a.getDouble("quality")
    val qF = if (f.isNull("quality")) null else f.getDouble("quality")
    val qOk = qA != null && qF != null && qA >= qF - 5
    return when {
        saving > 0 && qOk -> "Modo adaptativo consume %.1f%% menos con calidad similar.".format(saving)
        saving > 0 -> "Ahorra %.1f%% pero puede perder calidad.".format(saving)
        else -> "En estas condiciones no hay ahorro claro (%.1f%%).".format(saving)
    }
}

@Composable
fun CardBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .padding(14.dp),
    ) {
        content()
    }
    HorizontalDivider()
}

@Composable
fun MetricGrid(items: List<Pair<String, String>>) {
    Column {
        items.chunked(2).forEach { row ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                row.forEach { (l, v) ->
                    Column(Modifier.weight(1f).padding(4.dp)) {
                        Text(l, style = MaterialTheme.typography.labelSmall)
                        Text(v, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleTable(headers: List<String>, rows: List<List<String>>) {
    Column(Modifier.padding(top = 6.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
            headers.forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall) }
        }
        rows.forEach { row ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                row.forEach { cell ->
                    Text(cell, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
