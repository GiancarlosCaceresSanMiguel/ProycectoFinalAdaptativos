# EnergíaContexto

Gestor adaptativo de consumo energético según movilidad y actividad (propuesta 3).

La aplicación **predice cuánta batería se consumirá en los próximos 30 minutos** y
**adapta sus propias operaciones** (frecuencia de consulta del GPS y envío de datos por lotes)
para mantener un seguimiento útil con menor consumo.

## Versión móvil (Android)

Hay una app nativa en **Kotlin** en la carpeta [`android/`](android/README.md): misma lógica de predicción, políticas y panel. Puede usarse **solo en el teléfono** o enlazada al servidor Node mediante la URL del servidor.

## Cómo ejecutarla (web / servidor)

Solo necesita [Node.js 18 o superior](https://nodejs.org). **No hay que instalar paquetes ni compilar.**

```bash
cd EnergiaContexto
npm start          # o: node server.js
```

Abra **http://localhost:3000** en Chrome o Edge. (Otro puerto: `PORT=4000 npm start`.)

## Prueba rápida (2 minutos, sin salir de casa)

1. En *Sesión*, elija **Fuente: Simulada**, velocidad **600×** y pulse **Iniciar sesión**.
2. Deje correr ~15 s en modo **Fijo**, deténgala y repita en modo **Adaptativo** (use la misma actividad,
   por ejemplo "Reposo", y luego "Desplazamiento").
3. Abra *Panel de resultados*: compara consumo, consultas de GPS, envíos, retraso de sincronización
   y calidad del seguimiento entre modo fijo y adaptativo.
4. Con la sesión simulada puede probar: **sin conexión** (los datos se guardan y se sincronizan al volver),
   **cargando** (se excluye de la predicción) y **cerca de zona relevante** (sube la precisión).

Para tener un modelo entrenado hacen falta unos 150 ejemplos (≈ 3 horas simuladas de datos continuos);
con 600× se logra en pocos minutos. Mientras tanto se usa la referencia (extrapolar el consumo reciente).

## Uso con datos reales

Elija **Fuente: Real**. Usa la API de batería y la geolocalización del navegador (Chrome/Edge en
Android o en un portátil; Firefox y Safari no exponen la batería). Mantenga la pantalla encendida:
se solicita que no se apague, pero el navegador pausa la captura en segundo plano.

> **Importante:** el navegador solo permite GPS y batería en contextos seguros: `localhost` o `https`.
> Si abre la app desde el teléfono con `http://192.168.x.x:3000` esas funciones se bloquean. Opciones:
> - Android + cable USB: en el PC `adb reverse tcp:3000 tcp:3000` y abrir `http://localhost:3000` en el teléfono.
> - Un túnel HTTPS (por ejemplo ngrok: `ngrok http 3000`).
> - En Chrome del teléfono: `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, añadir la dirección y reiniciar.

Con datos reales la batería se mide en saltos de 1 %, por lo que se necesitan sesiones largas
(≥ 1 hora) y condiciones parecidas (misma actividad, pantalla, red) para comparar fijo y adaptativo.

## Archivos

| Archivo | Qué contiene |
| --- | --- |
| `server.js` | Servidor y servicios (telemetría, predicción, adaptación), intermediario de eventos, almacenamiento y estadísticas |
| `modelo.js` | Modelos: referencia, regresión lineal y árbol de decisión, con validación temporal |
| `public/shared.js` | Niveles, perfiles y **regla de adaptación** (la usan servidor y app) |
| `public/app.js` | App: captura, política, envío por lotes, modo sin conexión, panel |
| `public/index.html` | Interfaz y estilos |
| `package.json` | Solo define `npm start` |

Los datos se guardan en `data/datos.json` (se crea solo). El botón *Borrar historial* del panel lo elimina.

## Cómo funciona

**Perfiles:** *Mayor precisión*, *Equilibrio* y *Ahorro*. Cada uno tiene un nivel base y límites:

| Frecuencia | GPS cada | Envío cada |
| --- | --- | --- |
| Alta | 10 s | 30 s |
| Media | 30 s | 2 min |
| Baja | 2 min | 5 min |

**Reglas de adaptación** (modo adaptativo; el modo fijo siempre usa el nivel base):
- Sin movimiento → baja un nivel. Cerca de una zona relevante → sube uno.
- Consumo previsto alto (batería final < 20 % o bajada > 12 % en 30 min) → baja un nivel.
- Salvo batería crítica, si `velocidad × intervalo de GPS` supera 150 m, sube de nivel para no perder calidad.
- Cada decisión se explica en pantalla ("¿Por qué?"). Mínimo 3 minutos entre cambios.

**Eventos:** `TelemetriaRegistrada` → `ConsumoPredicho` → `PoliticaSeleccionada` → `PoliticaAplicada`
y `SincronizacionCompletada`. Cada evento tiene identificador; el servidor ignora los repetidos.

**Predicción:** variables conocidas en el momento de predecir (batería, consumo de los últimos 5 y 15 min,
velocidad, consultas de GPS y envíos por minuto; estos dos últimos reflejan la política aplicada).
La etiqueta es la bajada real 30 minutos después. Se entrena con lo más antiguo y se evalúa con lo más
reciente; un modelo solo se usa si mejora a la referencia al menos un 5 %. Los periodos de carga se excluyen.

**Si falla el servidor** o no hay conexión, la app sigue con la misma regla usando una extrapolación local
y lo indica en pantalla. Los datos pendientes se guardan en el dispositivo y se envían después.

**Privacidad:** no se envían coordenadas al servidor, solo velocidad aproximada; las zonas relevantes
se guardan únicamente en el dispositivo.

## Límites conocidos

- La simulación usa un modelo de consumo propio: sirve para probar el flujo, no sustituye mediciones reales.
- La app adapta únicamente sus propias operaciones, no las de otras aplicaciones.
- Para uso multiusuario faltaría autenticación; aquí se asume uso personal o de laboratorio.
