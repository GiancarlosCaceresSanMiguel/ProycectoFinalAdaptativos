/*
 * Modelos de predicción de consumo de batería.
 *  - Referencia: extrapolar el consumo reciente.
 *  - Regresión lineal (ridge).
 *  - Árbol de decisión pequeño.
 * Se entrena con lo que el sistema sabía EN ESE MOMENTO (sin mirar el futuro);
 * el futuro solo se usa para calcular la etiqueta.
 */
const { HORIZON_MIN } = require('./public/shared.js');
const MIN = 60000;
const MIN_SAMPLES = 150;

// Consumo medio por minuto mirando hasta k minutos atrás.
function rate(rec, byT, k) {
  for (let j = k; j >= 1; j--) {
    const prev = byT.get(rec.t - j * MIN);
    if (prev) return prev.charging ? null : (prev.battery - rec.battery) / j;
  }
  return null;
}

function features(rec, byT) {
  const r5 = rate(rec, byT, 5), r15 = rate(rec, byT, 15);
  if (r5 === null || r15 === null) return null;
  // batería, consumo últimos 5 y 15 min, velocidad, consultas GPS/min, envíos/min
  return [rec.battery, r5, r15, rec.speed, rec.fixes, rec.sends];
}

function baseline(rec, byT) {
  const r = rate(rec, byT, 10);
  return r === null ? null : Math.max(0, r * HORIZON_MIN);
}

// Construye ejemplos (entrada, etiqueta) a partir de las sesiones.
function buildDataset(groups) {
  const out = [];
  for (const g of groups) {
    for (const rec of g.list) {
      if (rec.charging) continue;
      const fut = g.byT.get(rec.t + HORIZON_MIN * MIN);
      if (!fut || fut.charging) continue;
      let ok = true;   // sin huecos ni cargas en medio
      for (let m = 1; m < HORIZON_MIN; m++) {
        const mid = g.byT.get(rec.t + m * MIN);
        if (!mid || mid.charging) { ok = false; break; }
      }
      if (!ok) continue;
      const x = features(rec, g.byT), base = baseline(rec, g.byT);
      if (!x || base === null) continue;
      out.push({ x, y: rec.battery - fut.battery, base });
    }
  }
  return out;
}

// ---------- Regresión lineal con regularización ----------
function fitLinear(X, y, lambda = 1) {
  const n = X.length, d = X[0].length, k = d + 1;
  const mean = Array(d).fill(0), std = Array(d).fill(0);
  for (const r of X) r.forEach((v, j) => { mean[j] += v / n; });
  for (const r of X) r.forEach((v, j) => { std[j] += (v - mean[j]) ** 2 / n; });
  for (let j = 0; j < d; j++) std[j] = Math.sqrt(std[j]) || 1;

  const A = Array.from({ length: k }, () => Array(k + 1).fill(0));   // [ZtZ | Zty]
  for (let r = 0; r < n; r++) {
    const z = [1, ...X[r].map((v, j) => (v - mean[j]) / std[j])];
    for (let i = 0; i < k; i++) {
      for (let j = 0; j < k; j++) A[i][j] += z[i] * z[j];
      A[i][k] += z[i] * y[r];
    }
  }
  for (let i = 1; i < k; i++) A[i][i] += lambda;
  const w = solve(A);
  return {
    predict: x => {
      let s = w[0];
      for (let j = 0; j < d; j++) s += w[j + 1] * (x[j] - mean[j]) / std[j];
      return s;
    }
  };
}

// Gauss-Jordan sobre la matriz aumentada.
function solve(A) {
  const k = A.length;
  for (let c = 0; c < k; c++) {
    let p = c;
    for (let r = c + 1; r < k; r++) if (Math.abs(A[r][c]) > Math.abs(A[p][c])) p = r;
    [A[c], A[p]] = [A[p], A[c]];
    const div = A[c][c] || 1e-9;
    for (let j = c; j <= k; j++) A[c][j] /= div;
    for (let r = 0; r < k; r++) {
      if (r === c) continue;
      const f = A[r][c];
      for (let j = c; j <= k; j++) A[r][j] -= f * A[c][j];
    }
  }
  return A.map(r => r[k]);
}

// ---------- Árbol de regresión ----------
function fitTree(X, y, maxDepth = 3, minLeaf = 10) {
  function build(idx, depth) {
    const mean = idx.reduce((s, i) => s + y[i], 0) / idx.length;
    if (depth === 0 || idx.length < 2 * minLeaf) return { v: mean };
    let best = null;
    for (let f = 0; f < X[0].length; f++) {
      const vals = idx.map(i => X[i][f]).sort((a, b) => a - b);
      for (let q = 1; q < 10; q++) {
        const thr = vals[Math.floor(vals.length * q / 10)];
        let nl = 0, sl = 0, ql = 0, nr = 0, sr = 0, qr = 0;
        for (const i of idx) {
          if (X[i][f] <= thr) { nl++; sl += y[i]; ql += y[i] * y[i]; }
          else { nr++; sr += y[i]; qr += y[i] * y[i]; }
        }
        if (nl < minLeaf || nr < minLeaf) continue;
        const sse = (ql - sl * sl / nl) + (qr - sr * sr / nr);
        if (!best || sse < best.sse) best = { sse, f, thr };
      }
    }
    if (!best) return { v: mean };
    return {
      f: best.f, thr: best.thr,
      l: build(idx.filter(i => X[i][best.f] <= best.thr), depth - 1),
      r: build(idx.filter(i => X[i][best.f] > best.thr), depth - 1)
    };
  }
  const root = build(X.map((_, i) => i), maxDepth);
  return {
    predict: x => {
      let n = root;
      while (n.v === undefined) n = x[n.f] <= n.thr ? n.l : n.r;
      return n.v;
    }
  };
}

const mae = a => a.length ? a.reduce((s, v) => s + Math.abs(v), 0) / a.length : null;

/*
 * Entrena con el 70 % más antiguo y evalúa con el 30 % más reciente.
 * Elige el modelo con menor error; si ninguno supera a la referencia, se usa la referencia.
 */
function train(samples) {
  const info = { samples: samples.length, chosen: 'baseline', mae: {}, trainedAt: Date.now(), note: '' };
  const cut = Math.floor(samples.length * 0.7);
  const tr = samples.slice(0, cut), te = samples.slice(cut);
  info.mae.baseline = mae(te.map(s => s.base - s.y));
  if (samples.length < MIN_SAMPLES) {
    info.note = 'Se necesitan al menos ' + MIN_SAMPLES + ' ejemplos (30 min de datos continuos cada uno). Mientras tanto se usa la referencia.';
    return { model: null, info };
  }
  const makers = { linear: (X, y) => fitLinear(X, y), tree: (X, y) => fitTree(X, y) };
  const X = tr.map(s => s.x), y = tr.map(s => s.y);
  let best = 'baseline', bestMae = info.mae.baseline * 0.95;   // debe ganar a la referencia por al menos 5 %
  for (const name of Object.keys(makers)) {
    const m = makers[name](X, y);
    info.mae[name] = mae(te.map(s => m.predict(s.x) - s.y));
    if (info.mae[name] < bestMae) { best = name; bestMae = info.mae[name]; }
  }
  info.chosen = best;
  info.note = 'Entrenado con ' + tr.length + ' ejemplos y evaluado con los ' + te.length + ' más recientes. Un modelo solo se usa si mejora a la referencia al menos un 5 %.';
  const model = best === 'baseline' ? null : makers[best](samples.map(s => s.x), samples.map(s => s.y));
  return { model, info };
}

module.exports = { features, baseline, buildDataset, train };
