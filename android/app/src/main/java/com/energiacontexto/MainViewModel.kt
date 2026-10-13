package com.energiacontexto

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.energiacontexto.client.AppPreferences
import com.energiacontexto.client.LatLon
import com.energiacontexto.client.SessionConfig
import com.energiacontexto.client.SessionEngine
import com.energiacontexto.client.SessionUiState
import com.energiacontexto.core.SharedConstants
import com.energiacontexto.location.LocationHelper
import com.energiacontexto.server.BackendServices
import com.energiacontexto.server.LocalDatabase
import com.energiacontexto.server.StatsComputer
import com.energiacontexto.service.CaptureForegroundService
import com.energiacontexto.sync.RemoteSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class MainUiState(
    val cfg: SessionConfig = SessionConfig("balance", "adaptive", "real", 60, 90.0),
    val session: SessionUiState = SessionUiState(),
    val tab: String = "sesion",
    val panelJson: JSONObject? = null,
    val panelError: String? = null,
    val panelProfileFilter: String = "",
    val serverUrl: String = "",
    val zoneCount: Int = 0,
    val simAct: String = "auto",
    val simOffline: Boolean = false,
    val simCharging: Boolean = false,
    val simNear: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val energia = app as EnergiaApp
    private val engine: SessionEngine = energia.sessionEngine
    private val backend: BackendServices = energia.backend
    private val db: LocalDatabase = energia.database
    private val prefs: AppPreferences = energia.prefs
    private val remote: RemoteSync = energia.remote
    private val location = LocationHelper(app)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var tickJob: Job? = null
    private var panelJob: Job? = null

    init {
        val cfg = prefs.loadCfg()
        _ui.value = MainUiState(
            cfg = cfg,
            serverUrl = prefs.loadServerUrl(),
            zoneCount = prefs.loadZones().size,
        )
        viewModelScope.launch { engine.recoverPending() }
    }

    fun setTab(tab: String) {
        _ui.update { it.copy(tab = tab) }
        panelJob?.cancel()
        if (tab == "panel") {
            panelJob = viewModelScope.launch {
                while (isActive) {
                    loadPanel()
                    delay(3000)
                }
            }
        }
    }

    fun updateCfg(block: (SessionConfig) -> SessionConfig) {
        _ui.update { st ->
            val cfg = block(st.cfg)
            prefs.saveCfg(cfg)
            st.copy(cfg = cfg)
        }
    }

    fun setServerUrl(url: String) {
        prefs.saveServerUrl(url)
        _ui.update { it.copy(serverUrl = url.trim()) }
    }

    fun setSimControls(act: String? = null, offline: Boolean? = null, charging: Boolean? = null, near: Boolean? = null) {
        _ui.update { st ->
            engine.simControls = engine.simControls.copy(
                act = act ?: st.simAct,
                offline = offline ?: st.simOffline,
                charging = charging ?: st.simCharging,
                near = near ?: st.simNear,
            )
            st.copy(
                simAct = act ?: st.simAct,
                simOffline = offline ?: st.simOffline,
                simCharging = charging ?: st.simCharging,
                simNear = near ?: st.simNear,
            )
        }
    }

    fun startSession() {
        if (engine.isRunning()) return
        viewModelScope.launch {
            engine.recoverPending()
            val cfg = _ui.value.cfg
            val batt = readBattery()
            val note = engine.start(
                cfg.copy(realBatteryPct = batt.first, realCharging = batt.second),
            ) { session -> _ui.update { it.copy(session = session) } }
            note?.let { n -> _ui.update { it.copy(session = it.session.copy(note = n)) } }
            if (cfg.source == "real") {
                val ctx = getApplication<Application>()
                ctx.startForegroundService(Intent(ctx, CaptureForegroundService::class.java).apply {
                    action = CaptureForegroundService.ACTION_START
                })
            }
            tickJob?.cancel()
            tickJob = viewModelScope.launch {
                while (isActive && engine.isRunning()) {
                    val now = System.currentTimeMillis()
                    refreshBatteryIfReal()
                    if (engine.needsFix()) {
                        requestLocationFix()
                    }
                    engine.tick(now, _ui.value.cfg) { session ->
                        _ui.update { it.copy(session = session) }
                    }
                    delay(1000)
                }
            }
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            tickJob?.cancel()
            engine.stop { session -> _ui.update { it.copy(session = session) } }
            val ctx = getApplication<Application>()
            ctx.stopService(Intent(ctx, CaptureForegroundService::class.java))
        }
    }

    fun markZone(): String? {
        if (!engine.isRunning()) return "Inicie una sesión primero."
        val pos = engine.lastPosition() ?: return "Aún no hay una posición. Espere a la primera consulta de GPS."
        addZoneAt(pos.lat, pos.lon)
        return null
    }

    fun addZoneAt(lat: Double, lon: Double) {
        val zones = prefs.loadZones().toMutableList()
        zones.add(LatLon(lat, lon))
        val trimmed = zones.takeLast(5)
        prefs.saveZones(trimmed)
        _ui.update { it.copy(zoneCount = trimmed.size) }
    }

    fun clearZones() {
        prefs.saveZones(emptyList())
        _ui.update { it.copy(zoneCount = 0) }
    }

    fun loadPanel() {
        viewModelScope.launch {
            try {
                val json = if (remote.useRemote()) {
                    remote.fetchStats(_ui.value.panelProfileFilter)
                } else {
                    StatsComputer.compute(db, _ui.value.panelProfileFilter)
                }
                _ui.update { it.copy(panelJson = json, panelError = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(panelError = "No se puede cargar el panel: ${e.message}") }
            }
        }
    }

    fun setPanelProfile(p: String) {
        _ui.update { it.copy(panelProfileFilter = p) }
        loadPanel()
    }

    fun retrain() {
        viewModelScope.launch {
            try {
                if (remote.useRemote()) remote.postTrain() else backend.retrain()
                loadPanel()
            } catch (_: Exception) { }
        }
    }

    fun resetHistory() {
        viewModelScope.launch {
            try {
                if (remote.useRemote()) {
                    remote.postReset()
                } else {
                    db.reset()
                    backend.retrain()
                }
                prefs.savePending(null)
                loadPanel()
            } catch (_: Exception) { }
        }
    }

    fun onLocationPermissionReady() {
        if (engine.isRunning() && engine.needsFix()) requestLocationFix()
    }

    fun requestLocationFix() {
        if (!engine.isRunning()) return
        val high = SharedConstants.LEVELS[engine.currentLevel()].alta
        location.requestFix(
            highAccuracy = high,
            onResult = { lat, lon, speed, acc ->
                engine.onLocation(lat, lon, speed, acc, System.currentTimeMillis())
                _ui.update { it.copy(session = engine.uiState()) }
            },
            onError = {
                engine.onGeoError()
                _ui.update { it.copy(session = engine.uiState()) }
            },
        )
    }

    private fun refreshBatteryIfReal() {
        if (_ui.value.cfg.source != "real") return
        val (pct, ch) = readBattery()
        engine.updateRealBattery(pct, ch)
    }

    private fun readBattery(): Pair<Double, Boolean> {
        val ctx = getApplication<Application>()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return level.toDouble() to charging
    }
}
