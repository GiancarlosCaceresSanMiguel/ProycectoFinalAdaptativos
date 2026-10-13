package com.energiacontexto.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.energiacontexto.MainViewModel
import com.energiacontexto.core.SharedConstants
import com.energiacontexto.core.SharedConstants.activityOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergiaScreen(vm: MainViewModel) {
    val state by vm.ui.collectAsState()
    val cfg = state.cfg
    val ses = state.session
    var showReset by remember { mutableStateOf(false) }
    var zoneNote by remember { mutableStateOf<String?>(null) }

    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.onLocationPermissionReady()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚡ EnergíaContexto") },
                actions = {
                    ServiceChip(ses)
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            val tabIdx = if (state.tab == "panel") 1 else 0
            TabRow(selectedTabIndex = tabIdx) {
                Tab(selected = tabIdx == 0, onClick = { vm.setTab("sesion") }, text = { Text("Sesión") })
                Tab(selected = tabIdx == 1, onClick = { vm.setTab("panel") }, text = { Text("Panel") })
            }
            when (state.tab) {
                "panel" -> PanelTab(state, vm, showReset, { showReset = it })
                else -> SessionTab(state, vm, cfg, ses, zoneNote, { zoneNote = it }, permLauncher, permissions)
            }
        }
    }
}

@Composable
private fun ServiceChip(ses: com.energiacontexto.client.SessionUiState) {
    val (label, ok) = when {
        !ses.running -> "Listo" to null
        !ses.online -> "Sin conexión" to false
        !ses.serverOk -> "Regla local" to false
        else -> "Conectado" to true
    }
    FilterChip(selected = false, onClick = {}, label = { Text(label) }, enabled = false)
}

@Composable
private fun SessionTab(
    state: com.energiacontexto.MainUiState,
    vm: MainViewModel,
    cfg: com.energiacontexto.client.SessionConfig,
    ses: com.energiacontexto.client.SessionUiState,
    zoneNote: String?,
    setZoneNote: (String?) -> Unit,
    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    permissions: List<String>,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(14.dp)) {
                DropdownLabel("Perfil", listOf("precision" to "Mayor precisión", "balance" to "Equilibrio", "saving" to "Ahorro"), cfg.profile, enabled = !ses.running) {
                    vm.updateCfg { c -> c.copy(profile = it) }
                }
                Text(SharedConstants.PROFILES[cfg.profile]?.ayuda ?: "", style = MaterialTheme.typography.bodySmall)
                DropdownLabel("Modo", listOf("adaptive" to "Adaptativo", "fixed" to "Fijo"), cfg.mode, enabled = !ses.running) {
                    vm.updateCfg { c -> c.copy(mode = it) }
                }
                DropdownLabel("Fuente", listOf("real" to "Real (GPS y batería)", "sim" to "Simulada"), cfg.source, enabled = !ses.running) {
                    vm.updateCfg { c -> c.copy(source = it) }
                }
                if (cfg.source == "sim") {
                    DropdownLabel("Velocidad sim", listOf("1" to "1×", "60" to "60×", "600" to "600×"), cfg.simSpeed.toString(), enabled = !ses.running) {
                        vm.updateCfg { c -> c.copy(simSpeed = it.toInt()) }
                    }
                    OutlinedNumber("Batería inicial %", cfg.simStart, !ses.running) {
                        vm.updateCfg { c -> c.copy(simStart = it.coerceIn(5.0, 100.0)) }
                    }
                }
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = vm::setServerUrl,
                    label = { Text("Servidor opcional (URL Node)") },
                    placeholder = { Text("Vacío = todo en el teléfono") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !ses.running,
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            fun go() = vm.startSession()
                            if (cfg.source == "real") {
                                permLauncher.launch(permissions.toTypedArray())
                                go()
                            } else go()
                        },
                        enabled = !ses.running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Iniciar sesión") }
                    Button(onClick = vm::stopSession, enabled = ses.running, modifier = Modifier.weight(1f)) { Text("Detener") }
                }
                val note = ses.note.ifEmpty { zoneNote ?: "" }
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (ses.running && ses.sim) {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text("Situación simulada", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    DropdownLabel("Actividad", listOf("auto" to "Automática", "reposo" to "Reposo", "caminata" to "Caminata", "desplazamiento" to "Desplazamiento"), state.simAct, true) {
                        vm.setSimControls(act = it)
                    }
                    CheckRow("Sin conexión", state.simOffline) { vm.setSimControls(offline = it) }
                    CheckRow("Cargando", state.simCharging) { vm.setSimControls(charging = it) }
                    CheckRow("Cerca de zona", state.simNear) { vm.setSimControls(near = it) }
                }
            }
        }

        if (ses.running && !ses.sim) {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text("Zonas relevantes (${state.zoneCount})", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { setZoneNote(vm.markZone()) }) { Text("Marcar zona") }
                        Button(onClick = vm::clearZones) { Text("Borrar") }
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(14.dp)) {
                MetricGrid(
                    listOf(
                        "Batería" to batText(ses),
                        "Bajada 30 min" to predText(ses),
                        "Frecuencia" to (SharedConstants.LEVELS.getOrNull(ses.level)?.nombre ?: "–") + if (ses.mode == "fixed") " (fijo)" else "",
                        "Actividad" to "${activityOf(ses.speed)} · ${"%.1f".format(ses.speed * 3.6)} km/h",
                        "GPS" to "cada ${fmtSec(ses.gpsSec)}",
                        "Envío" to "cada ${fmtSec(ses.batchSec)}",
                        "Pendientes" to "${ses.queueSize} eventos",
                        "Tiempo" to "${ses.sessionMin} min",
                    ),
                )
                Text("¿Por qué? ${ses.reason}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                BatteryChart(ses.history, ses.pred?.drop, ses.charging, Modifier.padding(top = 8.dp))
                Text("Azul: observada. Naranja: prevista en 30 min.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PanelTab(
    state: com.energiacontexto.MainUiState,
    vm: MainViewModel,
    showReset: Boolean,
    setShowReset: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                DropdownLabel("Filtrar perfil", listOf("" to "Todos", "precision" to "Mayor precisión", "balance" to "Equilibrio", "saving" to "Ahorro"), state.panelProfileFilter, true) {
                    vm.setPanelProfile(it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::retrain) { Text("Reentrenar") }
                    Button(onClick = { setShowReset(true) }) { Text("Borrar historial") }
                }
            }
        }
        if (showReset) {
            AlertDialog(
                onDismissRequest = { setShowReset(false) },
                title = { Text("Borrar historial") },
                text = { Text("Se eliminará el historial guardado. ¿Continuar?") },
                confirmButton = {
                    TextButton(onClick = { vm.resetHistory(); setShowReset(false) }) { Text("Sí") }
                },
                dismissButton = { TextButton(onClick = { setShowReset(false) }) { Text("No") } },
            )
        }
        state.panelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.panelJson?.let { StatsPanel(it) }
    }
}

@Composable
private fun DropdownLabel(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, title) ->
            FilterChip(
                selected = selected == value,
                onClick = { if (enabled) onSelect(value) },
                label = { Text(title, maxLines = 1) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun OutlinedNumber(label: String, value: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    OutlinedTextField(
        value = value.toInt().toString(),
        onValueChange = { v -> v.toDoubleOrNull()?.let(onChange) },
        label = { Text(label) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine = true,
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun batText(ses: com.energiacontexto.client.SessionUiState): String {
    if (!ses.running) return "–"
    val dec = if (ses.sim) 1 else 0
    return "%.${dec}f".format(ses.battery) + "%" + if (ses.charging) " ⚡" else ""
}

private fun predText(ses: com.energiacontexto.client.SessionUiState): String = when {
    !ses.running -> "–"
    ses.charging -> "No aplica (cargando)"
    ses.pred != null -> "−${"%.1f".format(ses.pred.drop)}% · ${ses.predModelLabel ?: ses.pred.model}"
    else -> "Reuniendo datos…"
}

private fun fmtSec(s: Int) = if (s >= 60) "${s / 60} min" else "$s s"
