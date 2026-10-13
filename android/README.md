# EnergíaContexto — Android (Kotlin)

App móvil nativa con la **misma lógica** que la versión web/Node:

- Captura GPS y batería (o simulación acelerada)
- Telemetría → predicción de bajada en 30 min → política adaptativa
- Modelos: referencia, regresión lineal y árbol de decisión
- Panel de estadísticas (fijo vs adaptativo)
- Modo sin conexión con cola local y sincronización posterior

## Modos de uso

1. **Solo teléfono (por defecto)**  
   Deje vacío el campo *Servidor opcional*. El backend (telemetría, ML y estadísticas) corre **dentro de la app**, igual que `server.js` + `modelo.js`.

2. **Con servidor Node existente**  
   En el PC: `npm start` y anote la URL de red, por ejemplo `http://192.168.1.10:3000`.  
   En la app, péguela en *Servidor opcional*. La app enviará eventos a `/api/sync` y el panel usará `/api/stats`.

## Requisitos

- Android Studio Ladybug o más reciente
- JDK 17
- Dispositivo o emulador **API 26+**

## Compilar e instalar

1. Abra la carpeta `android/` en Android Studio.
2. Espere la sincronización de Gradle.
3. **Run** en un dispositivo físico (recomendado para GPS y batería reales).

Permisos que pedirá la app: ubicación, notificaciones (Android 13+) para el servicio en primer plano durante sesiones reales.

## Estructura (equivalencia con el proyecto Node)

| Web / Node | Android |
| --- | --- |
| `public/shared.js` | `core/SharedConstants.kt`, `core/PolicyEngine.kt` |
| `modelo.js` | `ml/BatteryModel.kt` |
| `server.js` (servicios + stats) | `server/BackendServices.kt`, `server/StatsComputer.kt`, `server/LocalDatabase.kt` |
| `public/app.js` | `client/SessionEngine.kt` |
| `public/index.html` + panel | `ui/EnergiaScreen.kt`, `ui/PanelViews.kt`, `ui/BatteryChart.kt` |

## Prueba rápida (como en el README principal)

1. Fuente **Simulada**, velocidad **600×**, iniciar sesión.
2. Probar modo **Fijo** y **Adaptativo** con distintas actividades simuladas.
3. Pestaña **Panel**: comparar consumo y calidad.

## Notas

- No se envían coordenadas al servidor; solo velocidad aproximada (como en la web).
- Las zonas relevantes se guardan solo en el dispositivo (`SharedPreferences`).
- Para sesiones reales largas, mantenga la app en primer plano o minimizada con el servicio activo (notificación persistente).
