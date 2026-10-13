/*
 * EnergíaContexto - servidor.
 * Sin dependencias: solo módulos incluidos en Node.js.
 *
 * Aquí viven los "servicios" de la arquitectura, comunicados por un
 * intermediario de eventos en memoria:
 *   Telemetría -> Predicción -> Adaptación
 * Cada servicio solo escucha eventos y publica otros, por lo que se
 * podría separar en procesos distintos más adelante.
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { EventEmitter } = require('events');
const S = require('./public/shared.js');
const M = require('./modelo.js');

const PORT = process.env.PORT || 3000;
const PUBLIC = path.join(__dirname, 'public');
const DATA_DIR = path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'datos.json');
const MIN = 60000;

// ======================= Almacenamiento (archivo JSON) =======================
const emptyDb = () => ({ sessions: {}, records: [], predictions: [], policies: [], log: [], counters: {} });
let db = loadDb();
const idx = new Map();    // sessionId -> { list: [registros], byT: Map(t -> registro) }
const seen = new Set();   // ids de eventos ya procesados (evita duplicados)
let state = freshState();

function loadDb() {
  try { return Object.assign(emptyDb(), JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'))); }
  catch { return emptyDb(); }
}
function freshState() {
  return { model: null, lastTrainCount: 0, info: { samples: 0, chosen: 'baseline', mae: {}, note: 'Aún sin entrenar.' } };
}
let saveTimer = null;
function save(now) {
  if (saveTimer && !now) return;
  clearTimeout(saveTimer);
  const write = () => {
    saveTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      fs.writeFileSync(DATA_FILE + '.tmp', JSON.stringify(db));
      fs.renameSync(DATA_FILE + '.tmp', DATA_FILE);
    } catch (e) { console.error('No se pudo guardar:', e.message); }
  };
  if (now) write(); else saveTimer = setTimeout(write, 2000);
}
function indexRecord(r) {
  let g = idx.get(r.sessionId);
  if (!g) { g = { list: [], byT: new Map() }; idx.set(r.sessionId, g); }
  if (g.byT.has(r.t)) return false;
  g.list.push(r); g.byT.set(r.t, r);
  seen.add(r.id);
  return true;
}
db.records.forEach(indexRecord);

// ======================= Intermediario de eventos =======================
const bus = new EventEmitter();
const NOT_LOGGED = new Set(['PosicionActualizada', 'TelemetriaValidada']);
function publish(name, data, info) {
  db.counters[name] = (db.counters[name] || 0) + 1;
  if (!NOT_LOGGED.has(name)) {
    db.log.push({ name, t: Date.now(), session: data.sessionId, info: info || '' });
    if (db.log.length > 100) db.log.shift();
  }
  try { bus.emit(name, data); }
  catch (e) { console.error('Error en un servicio al procesar ' + name + ':', e.message); }
}

// ----- Servicio de telemetría: valida y guarda -----
bus.on('TelemetriaRegistrada', e => {
  const ok = [e.t, e.battery, e.speed, e.fixes, e.sends, e.gps, e.batch].every(Number.isFinite)
    && e.battery >= 0 && e.battery <= 100 && e.fixes >= 0 && e.sends >= 0;
  if (!ok) return;
  const rec = {
    id: e.id, sessionId: e.sessionId, t: e.t, battery: e.battery, charging: !!e.charging,
    speed: Math.max(0, e.speed), fixes: e.fixes, sends: e.sends, gps: e.gps, batch: e.batch, near: !!e.near
  };
  if (!indexRecord(rec)) return;
  db.records.push(rec);
  publish('TelemetriaValidada', { sessionId: rec.sessionId, record: rec });
});

// ----- Servicio de predicción: estima la bajada de batería en 30 min -----
function predictFor(rec, byT) {
  const base = M.baseline(rec, byT);
  if (base === null) return null;
  let drop = base, model = 'baseline';
  const x = M.features(rec, byT);
  if (state.model && x) { drop = state.model.predict(x); model = state.info.chosen; }
  return { drop: Math.min(Math.max(drop, 0), rec.battery), baseline: base, model };
}
bus.on('TelemetriaValidada', ({ sessionId, record }) => {
  let pred = null;
  if (!record.charging) {
    try { pred = predictFor(record, idx.get(sessionId).byT); }
    catch (e) { console.error('Falló el modelo, se usa la referencia:', e.message); state.model = null; }
  }
  if (pred) {
    db.predictions.push({ sessionId, t: record.t, battery: record.battery, predicted: pred.drop, baseline: pred.baseline, model: pred.model });
    if (db.predictions.length > 5000) db.predictions.shift();
  }
  publish('ConsumoPredicho', { sessionId, record, prediction: pred },
    pred ? 'bajada prevista ' + pred.drop.toFixed(1) + '% (' + pred.model + ')' : 'sin predicción');
  if (db.records.length - state.lastTrainCount >= 120) retrain();
});

// ----- Servicio de adaptación: elige la política -----
bus.on('ConsumoPredicho', ({ sessionId, record, prediction }) => {
  const ses = db.sessions[sessionId];
  if (!ses) return;
  const choice = S.choosePolicy({
    profile: ses.profile, mode: ses.mode, battery: record.battery,
    predictedDrop: prediction ? prediction.drop : null,
    speed: record.speed, nearZone: record.near, charging: record.charging
  });
  ses.prediction = prediction ? { drop: prediction.drop, model: prediction.model, t: record.t } : null;
  const first = ses.level === undefined;
  const dwellOk = first || record.t - ses.levelSince >= S.MIN_DWELL_MIN * MIN;
  if (first || (choice.level !== ses.level && dwellOk)) {
    ses.level = choice.level; ses.levelSince = record.t; ses.reason = choice.reason;
    db.policies.push({ sessionId, t: Date.now(), level: choice.level, reason: choice.reason });
    if (db.policies.length > 200) db.policies.shift();
    publish('PoliticaSeleccionada', { sessionId, level: choice.level }, 'frecuencia ' + S.LEVELS[choice.level].nombre.toLowerCase());
  } else if (choice.level === ses.level) {
    ses.reason = choice.reason;   // el nivel sigue siendo el adecuado: se actualiza la explicación
  }
});

// ----- Entrenamiento -----
function retrain() {
  const groups = [...idx.entries()]
    .map(([id, g]) => ({ start: (db.sessions[id] || {}).startedAt || 0, list: g.list.slice().sort((a, b) => a.t - b.t), byT: g.byT }))
    .sort((a, b) => a.start - b.start);
  const r = M.train(M.buildDataset(groups));
  state.model = r.model; state.info = r.info; state.lastTrainCount = db.records.length;
  console.log('Modelo entrenado:', r.info.chosen, '| ejemplos:', r.info.samples);
  return r.info;
}

// ======================= Sincronización con la app =======================
function httpError(status, msg) { const e = new Error(msg); e.status = status; return e; }

function handleSync(body) {
  const { sessionId, mode, profile, events } = body || {};
  if (typeof sessionId !== 'string' || !/^[\w:.-]{1,60}$/.test(sessionId)) throw httpError(400, 'sessionId inválido');
  if (!['adaptive', 'fixed'].includes(mode) || !S.PROFILES[profile]) throw httpError(400, 'modo o perfil inválido');
  if (!Array.isArray(events)) throw httpError(400, 'events debe ser una lista');

  const ses = db.sessions[sessionId] ||
    (db.sessions[sessionId] = { id: sessionId, mode, profile, startedAt: Date.now(), delaySum: 0, delayN: 0 });
  ses.mode = mode; ses.profile = profile;
  if (seen.size > 500000) seen.clear();

  let n = 0;
  for (const e of events) {
    if (!e || typeof e.id !== 'string' || seen.has(e.id)) continue;   // evento repetido: se ignora
    seen.add(e.id); n++;
    if (e.type === 'PosicionActualizada') {
      if (Number.isFinite(e.sentAt) && Number.isFinite(e.t)) { ses.delaySum += Math.max(0, (e.sentAt - e.t) / 1000); ses.delayN++; }
      publish('PosicionActualizada', { sessionId });
    } else if (e.type === 'TelemetriaRegistrada') {
      publish('TelemetriaRegistrada', Object.assign({}, e, { sessionId }), 'batería ' + e.battery + '%');
    } else if (e.type === 'PoliticaAplicada') {
      publish('PoliticaAplicada', { sessionId }, 'frecuencia ' + (S.LEVELS[e.level] ? S.LEVELS[e.level].nombre.toLowerCase() : '?'));
    }
  }
  publish('SincronizacionCompletada', { sessionId }, n + ' eventos nuevos');
  save();
  return {
    accepted: n,
    policy: ses.level === undefined ? null : { level: ses.level, reason: ses.reason },
    prediction: ses.prediction || null,
    model: { chosen: state.info.chosen, samples: state.info.samples }
  };
}

// ======================= Estadísticas del panel =======================
function computeStats(profile) {
  const newAcc = () => ({ min: 0, drain: 0, fixes: 0, sends: 0, ok: 0, delaySum: 0, delayN: 0 });
  const modes = { fixed: newAcc(), adaptive: newAcc() };
  const acts = {};
  let sessions = 0;

  for (const [id, g] of idx) {
    const ses = db.sessions[id];
    if (!ses || !modes[ses.mode] || (profile && ses.profile !== profile)) continue;
    sessions++;
    const a = modes[ses.mode];
    a.delaySum += ses.delaySum; a.delayN += ses.delayN;
    for (const b of g.list) {
      const prev = g.byT.get(b.t - MIN);
      if (b.charging || !prev || prev.charging) continue;
      const drain = prev.battery - b.battery;
      a.min++; a.drain += drain; a.fixes += b.fixes; a.sends += b.sends;
      if (b.speed * b.gps <= S.QUALITY_M) a.ok++;
      const act = S.activityOf(b.speed);
      acts[act] = acts[act] || { fixed: { min: 0, drain: 0 }, adaptive: { min: 0, drain: 0 } };
      acts[act][ses.mode].min++; acts[act][ses.mode].drain += drain;
    }
  }
  const perHour = (v, m) => m ? v / m * 60 : null;
  const outModes = {};
  for (const k of ['fixed', 'adaptive']) {
    const a = modes[k];
    outModes[k] = {
      minutes: a.min, drainPerHour: perHour(a.drain, a.min), fixesPerHour: perHour(a.fixes, a.min),
      sendsPerHour: perHour(a.sends, a.min), delaySec: a.delayN ? a.delaySum / a.delayN : null,
      quality: a.min ? a.ok / a.min * 100 : null
    };
  }
  const activities = {};
  for (const act of ['reposo', 'caminata', 'desplazamiento']) {
    const x = acts[act] || { fixed: { min: 0, drain: 0 }, adaptive: { min: 0, drain: 0 } };
    activities[act] = {};
    for (const k of ['fixed', 'adaptive']) activities[act][k] = { minutes: x[k].min, rate: perHour(x[k].drain, x[k].min) };
  }

  // Error de las predicciones ya comprobadas (cuando pasaron 30 min)
  let n = 0, e1 = 0, e2 = 0;
  for (const p of db.predictions) {
    const g = idx.get(p.sessionId);
    const fut = g && g.byT.get(p.t + S.HORIZON_MIN * MIN);
    if (!fut || fut.charging) continue;
    const actual = p.battery - fut.battery;
    n++; e1 += Math.abs(p.predicted - actual); e2 += Math.abs(p.baseline - actual);
  }
  return {
    counters: db.counters,
    totalEvents: Object.values(db.counters).reduce((s, v) => s + v, 0),
    sessions, records: db.records.length, model: state.info,
    live: { n, mae: n ? e1 / n : null, maeBase: n ? e2 / n : null },
    modes: outModes, activities,
    policies: db.policies.slice(-15).reverse(),
    log: db.log.slice(-25).reverse()
  };
}

// ======================= Servidor HTTP =======================
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png'
};
function sendJson(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > 5e6) { reject(httpError(413, 'Cuerpo demasiado grande')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); }
      catch { reject(httpError(400, 'JSON inválido')); }
    });
    req.on('error', reject);
  });
}
function serveStatic(res, pathname) {
  let rel;
  try { rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, ''); }
  catch { res.writeHead(400); return res.end(); }
  const file = path.join(PUBLIC, rel);
  if (!file.startsWith(PUBLIC + path.sep)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('No encontrado'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    if (req.method === 'POST' && p === '/api/sync') return sendJson(res, 200, handleSync(await readBody(req)));
    if (req.method === 'GET' && p === '/api/stats') return sendJson(res, 200, computeStats(url.searchParams.get('profile') || ''));
    if (req.method === 'POST' && p === '/api/train') { const info = retrain(); save(); return sendJson(res, 200, info); }
    if (req.method === 'POST' && p === '/api/reset') {
      db = emptyDb(); idx.clear(); seen.clear(); state = freshState(); save(true);
      return sendJson(res, 200, { ok: true });
    }
    if (req.method === 'GET' && p === '/api/health') return sendJson(res, 200, { ok: true });
    if (req.method === 'GET') return serveStatic(res, p);
    res.writeHead(405); res.end();
  } catch (e) {
    sendJson(res, e.status || 500, { error: e.message });
  }
});

retrain();
server.listen(PORT, '0.0.0.0', () => {
  console.log('\nEnergíaContexto en marcha');
  console.log('  Este equipo:  http://localhost:' + PORT);
  for (const list of Object.values(os.networkInterfaces()))
    for (const i of list) if (i.family === 'IPv4' && !i.internal) console.log('  En tu red:    http://' + i.address + ':' + PORT);
  console.log('\nCtrl+C para detener.\n');
});
process.on('SIGINT', () => { save(true); process.exit(0); });
process.on('SIGTERM', () => { save(true); process.exit(0); });
