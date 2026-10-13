/*
 * EnergíaContexto - aplicación (navegador).
 *
 * Idea central: un reloj "virtual" avanza de segundo en segundo (en la
 * simulación puede ir 60× o 600× más rápido). En cada segundo la app revisa
 * si toca: consultar el GPS, enviar un lote al servidor o cerrar el minuto
 * (registro de telemetría). El servidor responde con la política elegida.
 */
'use strict';
const { LEVELS, PROFILES, choosePolicy, activityOf } = Shared;

const $ = id => document.getElementById(id);
const round2 = v => Math.round(v * 100) / 100;
const fmtSec = s => (s >= 60 ? s / 60 + ' min' : s + ' s');
const ACTS = ['reposo', 'caminata', 'desplazamiento'];
const MODEL_NAMES = { baseline: 'Referencia (extrapolación)', linear: 'Regresión lineal', tree: 'Árbol de decisión' };

const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* sin almacenamiento */ } },
  del(k) { try { localStorage.removeItem(k); } catch { /* nada */ } }
};

const realAvailable = 'getBattery' in navigator && 'geolocation' in navigator;
const cfg = Object.assign({ profile: 'balance', mode: 'adaptive', source: 'real', speed: 60, start: 90 }, store.get('ec_cfg', {}));
if (!realAvailable) cfg.source = 'sim';
let zones = store.get('ec_zones', []);

let run = null;          // sesión en curso (null si no hay)
let queue = [];          // eventos pendientes de enviar
let timer = null;
let flushing = false;
let recovering = false;
let lastPersist = 0;

// ============================ Utilidades ============================
function setNote(t) { $('note').textContent = t || ''; }
function newId() { return run.id + ':' + (++run.seq); }
function isOnline(r = run) { return r.sim ? !$('simOffline').checked : navigator.onLine; }
function dist(a, b) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ============================ Sesión ============================
async function start() {
  if (run) return;
  cfg.profile = $('profile').value; cfg.mode = $('mode').value; cfg.source = $('source').value;
  cfg.speed = Number($('simSpeed').value); cfg.start = Math.min(100, Math.max(5, Number($('simStart').value) || 90));
  store.set('ec_cfg', cfg);
  await recoverPending();

  const sim = cfg.source === 'sim';
  let batt = null, wake = null;
  if (!sim) {
    try { batt = await navigator.getBattery(); }
    catch { setNote('No se pudo leer la batería de este dispositivo. Use la fuente simulada.'); return; }
    try { if (navigator.wakeLock) wake = await navigator.wakeLock.request('screen'); } catch { /* opcional */ }
  }
  const base = PROFILES[cfg.profile].base;
  const now = Date.now();
  const id = 's' + now;
  run = {
    id, sim, batt, wake, seq: 0,
    meta: { sessionId: id, mode: cfg.mode, profile: cfg.profile },
    startVt: now, vt: now, acc: 0, lastReal: now,
    nextFix: now, nextSend: now + LEVELS[base].batch * 1000, nextMin: now + 60000,
    level: base, gps: LEVELS[base].gps, batch: LEVELS[base].batch,
    counters: { fixes: 0, sends: 0, speedSum: 0, speedN: 0 },
    battery: sim ? cfg.start : batt.level * 100,
    charging: sim ? false : batt.charging,
    speed: 0, near: false, lastPos: null,
    history: [], pred: null, reason: 'Sesión iniciada con el nivel base del perfil.',
    serverOk: true, bg: 0, autoAct: 'reposo', exhausted: false, geoError: false
  };
  queue = [];
  setNote(sim ? '' : 'Mantenga esta pantalla abierta: el navegador pausa la captura en segundo plano.');
  timer = setInterval(tick, 1000);
  render();
}

async function stop(msg) {
  if (!run || run.stopping) return;
  const r = run;
  r.stopping = true;
  clearInterval(timer); timer = null;
  if (r.wake) { try { r.wake.release(); } catch { /* nada */ } }
  if (isOnline(r)) queue.forEach(e => { if (!e.sentAt) e.sentAt = r.vt; });
  while (flushing) await sleep(50);
  await flush(r);
  store.set('ec_pending', queue.length ? { meta: r.meta, events: queue } : null);
  queue = [];
  run = null;
  setNote(msg || 'Sesión detenida. Consulte los resultados en el panel.');
  render();
}

// ============================ Reloj virtual ============================
function tick() {
  if (!run) return;
  const now = Date.now();
  run.acc += (now - run.lastReal) / 1000 * (run.sim ? cfg.speed : 1);
  run.lastReal = now;
  let steps = 0;
  while (run.acc >= 1 && steps < 3000) { step(); run.acc -= 1; steps++; }
  if (run.acc >= 1) run.acc = 0;
  if (run.exhausted) { stop('La batería simulada se agotó. La sesión terminó.'); return; }
  if (now - lastPersist > 5000) { lastPersist = now; store.set('ec_pending', queue.length ? { meta: run.meta, events: queue } : null); }
  flush(run);
  render();
}

function step() {
  const r = run;
  r.vt += 1000;
  if (r.vt >= r.nextFix) { doFix(); r.nextFix = r.vt + r.gps * 1000; }
  if (r.vt >= r.nextSend) { doSend(); r.nextSend = r.vt + r.batch * 1000; }
  if (r.vt >= r.nextMin) { doMinute(); r.nextMin += 60000; }
}

// ============================ Captura ============================
function currentAct() { const a = $('simAct').value; return a === 'auto' ? run.autoAct : a; }
function simSpeed() {
  const a = currentAct();
  if (a === 'reposo') return 0;
  if (a === 'caminata') return Math.max(0.6, 1.4 + (Math.random() - 0.5) * 0.6);
  return 10 + (Math.random() - 0.5) * 4;
}

function addFix(r, speed) {
  r.speed = speed;
  r.counters.speedSum += speed; r.counters.speedN++;
  // Por privacidad no se envían coordenadas: solo la velocidad aproximada.
  queue.push({ id: newId(), type: 'PosicionActualizada', t: r.vt, speed: round2(speed) });
}

function doFix() {
  const r = run;
  r.counters.fixes++;
  if (r.sim) { addFix(r, simSpeed()); return; }
  navigator.geolocation.getCurrentPosition(pos => {
    if (run !== r) return;
    const p = { lat: pos.coords.latitude, lon: pos.coords.longitude, t: Date.now(), acc: pos.coords.accuracy || 0 };
    let sp = 0;
    if (typeof pos.coords.speed === 'number' && pos.coords.speed >= 0) sp = pos.coords.speed;
    else if (r.lastPos) {
      const d = dist(r.lastPos, p), dt = (p.t - r.lastPos.t) / 1000;
      if (dt > 0 && d > Math.max(20, p.acc)) sp = d / dt;   // ignora el ruido del GPS
    }
    r.lastPos = p;
    r.near = zones.some(z => dist(z, p) < 150);
    r.geoError = false;
    addFix(r, sp);
  }, () => { r.geoError = true; }, { enableHighAccuracy: LEVELS[r.level].alta, timeout: 10000, maximumAge: 0 });
}

// Envío por lotes: marca como "enviados" todos los eventos acumulados.
function doSend() {
  if (!isOnline()) return;
  let n = 0;
  for (const e of queue) if (!e.sentAt) { e.sentAt = run.vt; n++; }
  if (n) run.counters.sends++;
}

// Modelo sencillo de consumo para la simulación (% de batería por minuto).
function simBattery(r) {
  const c = r.counters;
  if (Math.random() < 0.03) r.bg = r.bg ? 0 : 0.08;   // carga de fondo ocasional
  if (r.charging) { r.battery = Math.min(100, r.battery + 0.5); return; }
  const act = currentAct();
  const actCost = act === 'reposo' ? 0 : act === 'caminata' ? 0.03 : 0.06;
  const fixCost = LEVELS[r.level].alta ? 0.012 : 0.008;
  const cost = 0.06 + c.fixes * fixCost + c.sends * 0.02 + actCost + r.bg;
  r.battery = Math.max(0, r.battery - cost * (0.85 + Math.random() * 0.3));
}

// Cada minuto: lee la batería y registra la telemetría.
function doMinute() {
  const r = run, c = r.counters;
  if (r.sim) {
    if (Math.random() < 0.1) r.autoAct = ACTS[Math.floor(Math.random() * 3)];
    r.charging = $('simCharging').checked;
    r.near = $('simNear').checked;
    simBattery(r);
  } else {
    r.battery = r.batt.level * 100;
    r.charging = r.batt.charging;
  }
  const speed = c.speedN ? c.speedSum / c.speedN : r.speed;
  r.speed = speed;
  queue.push({
    id: newId(), type: 'TelemetriaRegistrada', t: r.vt, battery: round2(r.battery), charging: r.charging,
    speed: round2(speed), fixes: c.fixes, sends: c.sends, gps: r.gps, batch: r.batch, near: r.near, online: isOnline()
  });
  r.history.push({ t: r.vt, battery: r.battery });
  if (r.history.length > 720) r.history.shift();
  r.counters = { fixes: 0, sends: 0, speedSum: 0, speedN: 0 };
  if (!isOnline() || !r.serverOk) localAdapt();   // sin servidor: regla básica local
  if (r.sim && !r.charging && r.battery <= 0.5) r.exhausted = true;
}

// ============================ Política ============================
function setLevel(level, reason) {
  const r = run;
  r.reason = reason;
  if (level === r.level) return;
  r.level = level; r.gps = LEVELS[level].gps; r.batch = LEVELS[level].batch;
  r.nextFix = Math.min(r.nextFix, r.vt + r.gps * 1000);
  r.nextSend = Math.min(r.nextSend, r.vt + r.batch * 1000);
  queue.push({ id: newId(), type: 'PoliticaAplicada', t: r.vt, level });
}

function localBaseline() {
  const h = run.history;
  if (h.length < 2) return null;
  const k = Math.min(10, h.length - 1);
  return Math.max(0, (h[h.length - 1 - k].battery - h[h.length - 1].battery) / k * 30);
}

function localAdapt() {
  const r = run, drop = localBaseline();
  const choice = choosePolicy({
    profile: r.meta.profile, mode: r.meta.mode, battery: r.battery, predictedDrop: drop,
    speed: r.speed, nearZone: r.near, charging: r.charging
  });
  r.pred = drop == null ? null : { drop, model: 'regla local' };
  setLevel(choice.level, '(Regla local) ' + choice.reason);
}

function applyServer(data) {
  if (!run) return;
  run.pred = data.prediction;
  run.modelName = data.model && data.model.chosen;
  if (data.policy) setLevel(data.policy.level, data.policy.reason);
}

// ============================ Comunicación ============================
async function postEvents(meta, events) {
  const r = await fetch('/api/sync', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: meta.sessionId, mode: meta.mode, profile: meta.profile, events })
  });
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.json();
}

async function flush(r) {
  if (flushing) return;
  const chunk = queue.filter(e => e.sentAt).slice(0, 2000);
  if (!chunk.length) return;
  flushing = true;
  try {
    const data = await postEvents(r.meta, chunk);
    const ids = new Set(chunk.map(e => e.id));
    queue = queue.filter(e => !ids.has(e.id));
    r.serverOk = true;
    if (run === r) applyServer(data);
  } catch {
    chunk.forEach(e => { delete e.sentAt; });   // se reintentará en el próximo envío
    r.serverOk = false;
  } finally { flushing = false; }
}

// Datos guardados localmente en una sesión anterior que no llegaron al servidor.
async function recoverPending() {
  if (run || recovering) return;
  const p = store.get('ec_pending', null);
  if (!p || !p.events || !p.events.length) return;
  recovering = true;
  try {
    p.events.forEach(e => { e.sentAt = Date.now(); });
    await postEvents(p.meta, p.events);
    store.del('ec_pending');
    setNote('Se sincronizaron los datos pendientes de la sesión anterior.');
  } catch { /* se reintentará más tarde */ }
  finally { recovering = false; }
}

// ============================ Pantalla ============================
function render() {
  const r = run;
  $('btnStart').disabled = !!r; $('btnStop').disabled = !r;
  ['profile', 'mode', 'source', 'simSpeed', 'simStart'].forEach(id => { $(id).disabled = !!r; });
  $('simBox').hidden = $('source').value !== 'sim';
  $('simLive').hidden = !(r && r.sim);
  $('realLive').hidden = !(r && !r.sim);
  $('zoneCount').textContent = zones.length;

  const svc = $('svc');
  if (!r) { svc.textContent = 'Listo'; svc.className = 'chip'; }
  else if (!isOnline()) { svc.textContent = 'Sin conexión: guardando localmente'; svc.className = 'chip warn'; }
  else if (!r.serverOk) { svc.textContent = 'Servicio no disponible: regla local'; svc.className = 'chip warn'; }
  else { svc.textContent = 'Servicio conectado'; svc.className = 'chip ok'; }

  if (!r) { drawChart(); return; }
  $('vBat').textContent = r.battery.toFixed(r.sim ? 1 : 0) + '%' + (r.charging ? ' ⚡' : '');
  if (r.charging) $('vPred').textContent = 'No aplica (cargando)';
  else if (r.pred) $('vPred').textContent = '−' + r.pred.drop.toFixed(1) + '% · ' + (MODEL_NAMES[r.pred.model] || r.pred.model);
  else $('vPred').textContent = 'Reuniendo datos…';
  $('vPol').textContent = LEVELS[r.level].nombre + (r.meta.mode === 'fixed' ? ' (fijo)' : '');
  $('vAct').textContent = activityOf(r.speed) + ' · ' + (r.speed * 3.6).toFixed(1) + ' km/h';
  $('vGps').textContent = 'cada ' + fmtSec(r.gps);
  $('vBatch').textContent = 'cada ' + fmtSec(r.batch);
  $('vQueue').textContent = queue.length + ' eventos';
  $('vTime').textContent = Math.floor((r.vt - r.startVt) / 60000) + ' min';
  $('vWhy').textContent = r.reason;
  if (r.geoError) setNote('No se pudo obtener la ubicación. Revise los permisos del navegador.');
  drawChart();
}

function drawChart() {
  const cv = $('chart'), ctx = cv.getContext && cv.getContext('2d');
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (!w || !h) return;
  if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const hs = run ? run.history.slice(-240) : [];
  const muted = getComputedStyle(document.body).color;
  ctx.fillStyle = muted; ctx.font = '11px system-ui';
  if (hs.length < 2) { ctx.fillText('Sin datos todavía', 10, 20); return; }

  const pred = run.pred && !run.charging ? run.pred.drop : null;
  const last = hs[hs.length - 1];
  const t0 = hs[0].t, t1 = last.t + (pred != null ? 30 * 60000 : 0);
  const vals = hs.map(p => p.battery).concat(pred != null ? [last.battery - pred] : []);
  const ymin = Math.max(0, Math.floor(Math.min(...vals) - 2)), ymax = Math.min(100, Math.ceil(Math.max(...vals) + 2));
  const L = 34, R = 8, T = 8, B = 20;
  const X = t => L + (t - t0) / (t1 - t0 || 1) * (w - L - R);
  const Y = v => T + (1 - (v - ymin) / (ymax - ymin || 1)) * (h - T - B);

  ctx.strokeStyle = 'rgba(128,128,128,.3)'; ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const v = ymin + (ymax - ymin) * i / 4, y = Y(v);
    ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke();
    ctx.fillText(Math.round(v) + '%', 2, y + 4);
  }
  ctx.fillText('0', L, h - 5);
  ctx.fillText(Math.round((t1 - t0) / 60000) + ' min', w - R - 40, h - 5);

  ctx.strokeStyle = '#2563eb'; ctx.lineWidth = 2; ctx.beginPath();
  hs.forEach((p, i) => { const x = X(p.t), y = Y(p.battery); if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y); });
  ctx.stroke();
  if (pred != null) {
    ctx.strokeStyle = '#ea580c'; ctx.setLineDash([5, 4]); ctx.beginPath();
    ctx.moveTo(X(last.t), Y(last.battery)); ctx.lineTo(X(t1), Y(last.battery - pred)); ctx.stroke();
    ctx.setLineDash([]);
  }
}

// ============================ Panel de resultados ============================
const n1 = v => (v == null ? '–' : Number(v).toFixed(1));
const n2 = v => (v == null ? '–' : Number(v).toFixed(2));
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const metric = (l, v) => '<div class="m"><span>' + l + '</span><b>' + esc(v) + '</b></div>';
function table(head, rows) {
  return '<div class="tablewrap"><table><tr>' + head.map(h => '<th>' + h + '</th>').join('') + '</tr>' +
    rows.map(r => '<tr>' + r.map(c => '<td>' + c + '</td>').join('') + '</tr>').join('') + '</table></div>';
}

function renderPanel(d) {
  const f = d.modes.fixed, a = d.modes.adaptive, m = d.model;
  let h = '<div class="card"><div class="grid">' +
    metric('Eventos procesados', d.totalEvents) + metric('Sesiones', d.sessions) +
    metric('Registros de telemetría', d.records) + metric('Modelo activo', MODEL_NAMES[m.chosen] || m.chosen) + '</div></div>';

  h += '<div class="card"><h3 style="margin-top:0">Predicción de la bajada de batería en 30 min</h3>' +
    table(['Modelo', 'Error medio (puntos de batería)'], [
      ['Referencia: extrapolar el consumo reciente', n2(m.mae.baseline)],
      ['Regresión lineal', n2(m.mae.linear)],
      ['Árbol de decisión', n2(m.mae.tree)]
    ]) +
    '<p class="hint">' + esc(m.note || '') + ' Ejemplos disponibles: ' + m.samples + '.</p>' +
    '<p>Predicciones ya comprobadas (' + d.live.n + '): error medio del sistema <b>' + n2(d.live.mae) +
    '</b> frente a <b>' + n2(d.live.maeBase) + '</b> de la referencia.</p></div>';

  // Comparación en igualdad de condiciones: se pondera por los minutos de cada actividad en modo adaptativo.
  let wf = 0, wa = 0;
  for (const act of Object.keys(d.activities)) {
    const x = d.activities[act];
    if (x.fixed.minutes >= 5 && x.adaptive.minutes >= 5) { wf += x.adaptive.minutes * x.fixed.rate; wa += x.adaptive.minutes * x.adaptive.rate; }
  }
  let verdict = 'Aún no hay datos suficientes de ambos modos. Ejecute sesiones en modo fijo y en modo adaptativo con las mismas actividades (al menos 5 minutos de cada actividad).';
  if (wf > 0) {
    const saving = (1 - wa / wf) * 100;
    const qOk = a.quality != null && f.quality != null && a.quality >= f.quality - 5;
    verdict = 'Con la misma mezcla de actividades, el modo adaptativo consume <b>' + n1(saving) + '%</b> ' + (saving >= 0 ? 'menos' : 'más') +
      ' que el fijo. Calidad del seguimiento: ' + n1(a.quality) + '% (adaptativo) frente a ' + n1(f.quality) + '% (fijo). ' +
      (saving > 0 && qOk ? '<b>Es una mejora válida:</b> ahorra batería sin perder calidad.'
        : saving > 0 ? 'Ahorra batería, pero pierde calidad: no cuenta como mejora.'
        : (a.quality != null && f.quality != null && a.quality > f.quality + 5)
          ? 'No ahorra batería, pero mejora la calidad del seguimiento en ' + n1(a.quality - f.quality) + ' puntos (gasta más donde hace falta precisión).'
          : 'No hay ahorro en estas condiciones.');
  }
  h += '<div class="card"><h3 style="margin-top:0">Fijo frente a adaptativo</h3>' +
    table(['Indicador', 'Fijo', 'Adaptativo'], [
      ['Minutos observados', f.minutes, a.minutes],
      ['Consumo (% de batería por hora)', n2(f.drainPerHour), n2(a.drainPerHour)],
      ['Consultas de GPS por hora', n1(f.fixesPerHour), n1(a.fixesPerHour)],
      ['Envíos por hora', n1(f.sendsPerHour), n1(a.sendsPerHour)],
      ['Retraso medio de sincronización (s)', n1(f.delaySec), n1(a.delaySec)],
      ['Calidad del seguimiento (%)', n1(f.quality), n1(a.quality)]
    ]) + '<p class="hint">Calidad: porcentaje de minutos con error de posición estimado (velocidad × intervalo de GPS) menor a 150 m.</p>' +
    '<div class="verdict">' + verdict + '</div>' +
    '<h3>Consumo por actividad (% por hora)</h3>' +
    table(['Actividad', 'Fijo', 'Adaptativo'], Object.keys(d.activities).map(act => {
      const x = d.activities[act];
      return [act, n2(x.fixed.rate) + ' <span class="hint">(' + x.fixed.minutes + ' min)</span>', n2(x.adaptive.rate) + ' <span class="hint">(' + x.adaptive.minutes + ' min)</span>'];
    })) + '</div>';

  h += '<div class="card"><h3 style="margin-top:0">Últimos cambios de política</h3>' +
    (d.policies.length ? table(['Hora', 'Frecuencia', 'Motivo'], d.policies.map(p =>
      [new Date(p.t).toLocaleTimeString(), LEVELS[p.level].nombre, esc(p.reason)])) : '<p class="hint">Todavía no hay cambios.</p>') + '</div>';

  h += '<div class="card"><h3 style="margin-top:0">Eventos recientes</h3>' +
    '<p class="hint">' + Object.keys(d.counters).map(k => k + ': ' + d.counters[k]).join(' · ') + '</p>' +
    (d.log.length ? table(['Hora', 'Evento', 'Detalle'], d.log.map(e =>
      [new Date(e.t).toLocaleTimeString(), esc(e.name), esc(e.info)])) : '') + '</div>';
  $('panelBody').innerHTML = h;
}

let panelTimer = null;
async function loadPanel() {
  try {
    const p = $('fProfile').value;
    const r = await fetch('/api/stats' + (p ? '?profile=' + p : ''));
    renderPanel(await r.json());
  } catch { $('panelBody').innerHTML = '<p class="hint">No se puede contactar con el servidor.</p>'; }
}
function showTab(name) {
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
  $('tab-sesion').hidden = name !== 'sesion';
  $('tab-panel').hidden = name !== 'panel';
  clearInterval(panelTimer);
  if (name === 'panel') { loadPanel(); panelTimer = setInterval(loadPanel, 3000); }
  else drawChart();
}

// ============================ Arranque ============================
function init() {
  $('profile').value = cfg.profile; $('mode').value = cfg.mode;
  $('source').value = cfg.source; $('simSpeed').value = String(cfg.speed); $('simStart').value = cfg.start;
  if (!realAvailable) {
    $('source').querySelector('option[value=real]').disabled = true;
    setNote('Este navegador no permite leer la batería o el GPS (use Chrome o Edge). Se usará la simulación.');
  }
  const help = () => { $('profileHelp').textContent = PROFILES[$('profile').value].ayuda; };
  $('profile').addEventListener('change', help); help();
  $('source').addEventListener('change', render);
  $('btnStart').addEventListener('click', start);
  $('btnStop').addEventListener('click', () => stop());
  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));
  $('fProfile').addEventListener('change', loadPanel);

  $('btnZone').addEventListener('click', () => {
    if (!run || !run.lastPos) { setNote('Aún no hay una posición. Espere a la primera consulta de GPS.'); return; }
    zones.push({ lat: run.lastPos.lat, lon: run.lastPos.lon });
    zones = zones.slice(-5); store.set('ec_zones', zones); render();
  });
  $('btnClearZones').addEventListener('click', () => { zones = []; store.set('ec_zones', zones); render(); });

  $('btnTrain').addEventListener('click', async () => { await fetch('/api/train', { method: 'POST' }); loadPanel(); });
  $('btnReset').addEventListener('click', async () => {
    if (!confirm('Se eliminará todo el historial guardado en el servidor. ¿Continuar?')) return;
    await fetch('/api/reset', { method: 'POST' });
    store.del('ec_pending'); loadPanel();
  });
  window.addEventListener('online', recoverPending);
  window.addEventListener('resize', drawChart);
  recoverPending();
  render();
}
init();
