/*
 * Constantes y reglas compartidas entre el servidor y la aplicación.
 * Se usa en dos sitios: el servidor (require) y el navegador (<script>).
 * Así la regla de adaptación es la misma si el servidor falla y la app
 * debe decidir sola.
 */
(function (root) {
  const HORIZON_MIN = 30;   // horizonte de predicción (minutos)
  const QUALITY_M = 150;    // error de posición máximo aceptable (metros)
  const MIN_DWELL_MIN = 3;  // minutos mínimos entre cambios de política

  // Niveles de captura/comunicación. gps y batch están en segundos.
  const LEVELS = [
    { nombre: 'Baja', gps: 120, batch: 300, alta: false },
    { nombre: 'Media', gps: 30, batch: 120, alta: false },
    { nombre: 'Alta', gps: 10, batch: 30, alta: true }
  ];

  // Cada perfil define su nivel base y los límites en que se puede mover.
  const PROFILES = {
    precision: {
      nombre: 'Mayor precisión', base: 2, min: 1, max: 2,
      ayuda: 'Prioriza la calidad del seguimiento. Solo baja a frecuencia media si la batería está en riesgo.'
    },
    balance: {
      nombre: 'Equilibrio', base: 1, min: 0, max: 2,
      ayuda: 'Sube la frecuencia cerca de zonas relevantes o a gran velocidad, y la baja en reposo o con consumo alto.'
    },
    saving: {
      nombre: 'Ahorro', base: 0, min: 0, max: 1,
      ayuda: 'Usa frecuencia baja casi siempre y solo sube a media cerca de zonas relevantes.'
    }
  };

  function activityOf(speed) {
    if (speed < 0.5) return 'reposo';
    if (speed < 4) return 'caminata';
    return 'desplazamiento';
  }

  /*
   * Motor de adaptación. Devuelve el nivel (0, 1 o 2) y el motivo.
   * c = { profile, mode, battery, predictedDrop, speed, nearZone, charging }
   */
  function choosePolicy(c) {
    const p = PROFILES[c.profile];
    let lvl = p.base;
    const clamp = v => Math.max(p.min, Math.min(p.max, v));

    if (c.mode === 'fixed') {
      return { level: lvl, reason: 'Modo fijo: se mantiene siempre el nivel base del perfil (' + LEVELS[lvl].nombre + ').' };
    }
    if (c.charging) {
      return { level: lvl, reason: 'El teléfono está cargando: se usa el nivel base del perfil (' + LEVELS[lvl].nombre + ').' };
    }

    const why = [];
    const drop = c.predictedDrop == null ? 0 : c.predictedDrop;
    if (c.predictedDrop == null) why.push('aún no hay predicción');

    if (c.nearZone) { lvl = clamp(lvl + 1); why.push('cerca de una zona relevante'); }
    else if (c.speed < 0.5) { lvl = clamp(lvl - 1); why.push('sin movimiento'); }

    const left = c.battery - drop;   // batería prevista dentro de 30 min
    if (left < 20 || drop > 12) {
      lvl = clamp(lvl - 1);
      why.push('consumo previsto alto (' + drop.toFixed(1) + '% en 30 min)');
    }

    // Salvo batería crítica, se protege la calidad del seguimiento.
    if (left >= 10) {
      let raised = false;
      while (lvl < p.max && c.speed * LEVELS[lvl].gps > QUALITY_M) { lvl++; raised = true; }
      if (raised) why.push('la velocidad exige más muestreo');
    }

    return {
      level: lvl,
      reason: 'Frecuencia ' + LEVELS[lvl].nombre.toLowerCase() + ': ' + (why.length ? why.join('; ') : 'condiciones normales') + '.'
    };
  }

  const api = { HORIZON_MIN, QUALITY_M, MIN_DWELL_MIN, LEVELS, PROFILES, activityOf, choosePolicy };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Shared = api;
})(typeof window !== 'undefined' ? window : globalThis);
