# ASSISTANT // Funcionalidades, Integraciones y Arquitectura del Ecosistema

Guía técnica y manual de usuario de las integraciones activas en **ASSISTANT** para la tablet **Samsung Galaxy Tab 4 (`SM-T535`, Android 5.0.2 Lollipop / API 21)** en modo electrodoméstico (*appliance*), 100% local, sin cámara y con portal web en red doméstica (`http://192.168.1.171:8080/`).

---

## 1. Sesiones de Carrera y Cardio en Harbiz (`type: "cardio"` + `cardioText`)

### Por qué antes no aparecían las carreras planificadas en Harbiz
En el protocolo Meteor DDP de Harbiz (`wss://app.harbiz.io/websocket`), los entrenamientos de gimnasio y los de carrera llegan con esquemas distintos dentro de la colección de eventos del calendario:
- **Fuerza**: eventos con `type: "workout"` y árbol `workout.exercises` (series, repeticiones y kilos).
- **Carrera / Cardio**: eventos con `type: "cardio"` (o con metadatos `cardio`, `cardioText` y `resultCardio` / `resultGeneral`), donde la prescripción del entrenador viene como texto enriquecido en `cardioText` (por ejemplo, `<p>40' Z2 rodaje suave</p>`) y la ejecución completada se guarda en `resultCardio` / `resultGeneral`.

Originalmente el parser filtraba únicamente `type == "workout"`, descartando silenciosamente cualquier sesión de carrera planificada en Harbiz.

### Cómo funciona ahora el pipeline (`HarbizParser` → `SyncCoordinator` → `StravaAnalyzer`)
1. **Descubrimiento y clasificación (`HarbizParser.kt`)**:
   - `discoverWorkoutEvents` y `parseWorkoutDetail` aceptan tanto `type: "workout"` como `type: "cardio"`, además de detectar contenido cardio mediante `hasCardioContent` (`cardioText`, `resultCardio`, objeto/texto `cardio`) y vocabulario específico (`run`, `running`, `carrera`, `rodaje`, `series`, `fartlek`, `tempo`, `cuestas`, `trail`, `km`, `z1–z5`, `cardio`).
   - El campo `HarbizWorkout.isCardioOrRun` marca la sesión como carrera/cardio y `summarySubtitle` limpia las etiquetas HTML de `cardioText` (o resume `resultCardio` / `distance` / `duration` / `zone`) hasta un máximo de 60 caracteres legibles.
2. **Agenda y Entreno de Hoy (`SyncCoordinator.buildTodayTrainings`)**:
   - En el panel **`03 // AGENDA & ENTRENO DE HOY`**, las sesiones de fuerza se muestran estrictamente como `[ENTRENO FUERZA] // <TÍTULO>` (sin desplegar listas de ejercicios ni pesos).
   - Las sesiones de carrera planificadas o completadas en Harbiz se muestran como `[CARRERA] // <TÍTULO> — <SUBTÍTULO>` (p. ej. `[CARRERA] // RODAJE — 40' Z2`).
   - **Deduplicación con Strava**: si el mismo día existe una carrera sincronizada en Strava con el mismo título (o en el recuento semanal, una actividad de carrera en Strava ese mismo día), `SyncCoordinator` y `StravaAnalyzer.buildWeekSummary` evitan contarla dos veces, priorizando los datos telemétricos reales de Strava (distancia, ritmo y zona cardíaca dominante).
3. **Progreso Semanal (`04 // ENTRENOS SEMANA (DESDE EL LUNES)`)**:
   - `StravaAnalyzer.buildWeekSummary` marca el punto circular relleno (`●`) en la tira lunes-domingo (`WeekStripView`) cuando hay carrera en Strava **o** un evento `isCardioOrRun` completado en Harbiz, y suma la sesión en `runningCount` y `completedSessionsCount` sin duplicar días ya registrados por Strava.

> **Nota para Strava OAuth**: al autorizar Strava desde `http://192.168.1.171:8080/`, mantén marcada la casilla **«View data about your private activities»** para conceder el permiso `activity:read_all`. Si realizas la configuración desde un ordenador en la misma Wi-Fi o conectado por USB, puedes abrir directamente `http://192.168.1.171:8080/` en la LAN sin necesidad de redirección de puertos en `localhost`.

---

## 2. Gráfica MSCI World (30 Días) y Watchlist de Acciones (`MarketProvider`)

### Fuente de datos y resiliencia
`MarketProvider.kt` consulta directamente el endpoint público **Yahoo Finance v8 Chart API** (`https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1mo&interval=1d`) utilizando la pila TLS 1.2 moderna de la app (`Conscrypt` + bundle de CA roots en `Tls12SocketFactory.kt`, compatible con Android 5.0.2):
- **Sin API key ni cuentas de terceros**: cero coste y ejecución 100% directa desde la tablet.
- **Índice principal por defecto**: **`IWDA.AS`** (*iShares Core MSCI World UCITS ETF* cotizado en EUR en Euronext Amsterdam).
- **Fallback automático**: si `IWDA.AS` no responde o devuelve error temporal, `MarketProvider` solicita automáticamente **`URTH`** (*iShares MSCI World ETF* en USD) e indica el estado `ALTERNATIVA MSCI WORLD`.
- **Cálculo de métricas**: extrae la serie de cierres diarios de los últimos 30 días (`sparklineCloses`), el precio actual (`regularMarketPrice`), la variación diaria (`1D %`) y la variación mensual (`1M %`).

### Visualización en la Tablet (`05 // MERCADOS (MSCI WORLD & ACCIONES)`)
- **Cabecera**: símbolo e índice principal, precio actual con divisa y variación `1D` / `1M`.
- **Gráfica 30D (`MarketSparklineView`)**: curva suavizada monocroma en blanco sobre rejilla punteada horizontal, relleno inferior en matriz de puntos (*dot-matrix*) con degradado vertical y etiquetas `MAX`, `MIN` y `30D`.
- **Watchlist compacta**: hasta 4 acciones/ETFs/criptos en la parte inferior del panel (por defecto `AAPL`, `MSFT`, `NVDA`).

### Gestión desde el Portal Web Local (`http://192.168.1.171:8080/`)
En la tarjeta **`07 // MERCADOS (MSCI WORLD & ACCIONES)`**:
1. **Cambiar índice principal**: selecciona `IWDA.AS` (MSCI World EUR), `URTH` (MSCI World USD), `^990100-USD-STRD` (índice MSCI World), `SPY` (S&P 500) o `QQQ` (Nasdaq 100) y pulsa **CAMBIAR ÍNDICE** (`POST /api/market` con `action: "set_primary"`).
2. **Añadir / eliminar tickers**: introduce cualquier símbolo de Yahoo Finance (acciones USA como `AAPL`, europeas como `SAN.MC` o `ITX.MC`, ETFs o criptomonedas como `BTC-USD`) y pulsa **+ AÑADIR ACCIÓN** (`action: "add"`) o **`[X]`** (`action: "remove"`). La tablet sincroniza automáticamente en segundo plano.

---

## 3. Análisis de Viabilidad: ¿Puede la Tablet (`SM-T535`) ser Host de Servicios Semyra? + Monitor de Conectividad (`SemyraServiceMonitor`)

### Capacidad real del hardware (`SM-T535`) frente a los requisitos de Semyra

| Dimensión | Samsung Galaxy Tab 4 (`SM-T535`) | Ecosistema Backend Semyra |
| :--- | :--- | :--- |
| **Arquitectura CPU** | ARMv7 32-bit (`armeabi-v7a`, Snapdragon 400 Quad-Core 1.2 GHz) | `x86_64` / `arm64` 64-bit obligatorio |
| **Memoria RAM** | 1.5 GB nominal (~1.38 GB utilizables por el kernel) | > 512 MB–1.5 GB por runtime + base de datos |
| **Sistema / Kernel** | Android 5.0.2 Lollipop (API 21, Linux Kernel 3.4, sin `seccomp` moderno ni namespaces de contenedores) | Linux moderno / Cloudflare Edge |
| **`semyra-sport` y `semyra-nutrition`** | Incompatible | **Cloudflare Workers (`workerd` 64-bit)** + D1 (SQLite distribuido) + R2 + **Node.js ≥ 22.13** |
| **`migraine`** | Incompatible | **Node.js ≥ 24.19** + **Next.js** (SSR/RSC) + **PostgreSQL** |

### Conclusión técnica
- **NO puede alojar los runtimes de cómputo backend de Semyra**: ni `workerd` (64-bit), ni Node 22/24 (que requieren glibc/bionic moderno y kernels ≥ 4.x en 64-bit), ni PostgreSQL + Next.js son ejecutables de forma estable o segura en un Snapdragon 400 de 32-bit con Android 5.0.2 y 1.38 GB de RAM.
- **SÍ es ideal como Host Local de Control y Telemetría del Ecosistema Semyra**: estando siempre encendida y conectada a la Wi-Fi doméstica, la tablet actúa como consola de observabilidad y servidor LAN ultraligero (`LocalConfigServer :8080`).

### Monitor de Conectividad en Tiempo Real (`SemyraServiceMonitor.kt`)
Durante cada ciclo de sincronización, `SemyraServiceMonitor` ejecuta sondas HTTP en paralelo (pool de 4 hilos, timeout de 3 segundos, método `HEAD` con fallback a `GET` en 403/405/501) y mide la latencia exacta en milisegundos (`ms`) para:
1. **Harbiz DDP** (`https://app.harbiz.io/` o resultado real del último sync DDP).
2. **Strava API** (`https://www.strava.com/api/v3` o resultado real del último sync OAuth).
3. **Semyra Sport** (`config.semyraApiUrl`).
4. **Telegram Bot** (`https://api.telegram.org/` + estado real del long-polling del bot).
5. **Open-Meteo** (telemetría del último fetch meteorológico).
6. **OSRM** (telemetría del motor de rutas en coche).
7. **Endpoints Semyra personalizados**: cualquier lista de URLs que añadas en la tarjeta **`08 // DOMÓTICA & SERVICIOS SEMYRA`** (`semyraServiceUrls`), como los endpoints `/api/health` de `semyra-sport`, `semyra-nutrition` o `migraine`.

El resultado se muestra de forma compacta en **`06 // DOMÓTICA TAPO & ESTADO SEMYRA`** (`●` en línea con latencia en `ms` / `○` sin conexión) y con detalle completo HTTP en el portal web.

---

## 4. Conector de Domótica: Bombillas TP-Link Tapo y Webhooks Alexa (`TapoSmartHomeClient`)

`TapoSmartHomeClient.kt` permite encender y apagar bombillas inteligentes directamente desde la pantalla táctil de la tablet (panel **`06 // DOMÓTICA TAPO & ESTADO SEMYRA`**) o desde el portal web LAN (`:8080`), soportando dos modos complementarios:

### Modo A: Control Directo en LAN TP-Link Tapo (Puerto 80, sin nube)
La tablet se comunica directamente con la IP local de cada bombilla (`http://<IP_LOCAL>/app`) restringida estrictamente a rangos privados RFC 1918 (`10.x`, `172.16-31.x`, `192.168.x`) y con `Proxy.NO_PROXY`. Soporta automáticamente los tres protocolos de firmware de TP-Link Tapo:
1. **KLAP v2** (firmwares recientes): handshake binario en `/app/handshake1` y `/app/handshake2` con hash `SHA-256(SHA-1(email) + SHA-1(password))`, derivación de claves `lsk`/`iv`/`ldk` y cifrado `AES-128-CBC` firmado con SHA-256 y número de secuencia.
2. **KLAP v1** (firmwares intermedios): detección automática de firma MD5 durante `handshake1`.
3. **Secure Passthrough (Legacy RSA + AES)**: si la bombilla no soporta KLAP, negocia una clave de sesión AES mediante par RSA-1024 efímero y envía `login_device` + `set_device_info` / `get_device_info`.

#### Pasos previos de configuración para bombillas Tapo
1. **Activar compatibilidad local en la app Tapo del móvil**:
   - Abre la app **TP-Link Tapo** → pestaña **Yo (Me)** → **Servicios de terceros (Third-Party Services)** → activa **Compatibilidad con terceros (Third-Party Compatibility)**.
2. **Asignar IP fija (DHCP estático) en tu router**:
   - Entra en el panel de tu router doméstico y reserva una IP fija para la dirección MAC de cada bombilla Tapo (por ejemplo `192.168.1.55` para Salón, `192.168.1.56` para Despacho), evitando que cambie tras cortes de luz.
3. **Registrar credenciales y dispositivos en `http://192.168.1.171:8080/`**:
   - En la tarjeta **`08 // DOMÓTICA (BOMBILLAS TAPO / ALEXA) & SERVICIOS SEMYRA`**, guarda tu **Cuenta TP-Link Tapo Email** y **Contraseña Tapo** (se almacenan localmente en `context.filesDir` con `allowBackup="false"` y se redactan en las respuestas JSON del portal).
   - Añade cada bombilla indicando su **Nombre** y su **IP local Tapo**. En cada sincronización la tablet lee el estado real (`get_device_info`), y al tocar el botón `[ ☀ SALÓN: ON ]` en la tablet el cambio es instantáneo en LAN.

### Modo B: Webhooks para Dispositivos Exclusivos de Alexa / Home Assistant / IFTTT
Si una bombilla o enchufe solo funciona dentro de Amazon Alexa (sin protocolo local Tapo):
- Configura un trigger gratuito en **Voice Monkey** (rutina de Alexa disparada por URL), un webhook de **Home Assistant** o un applet de **IFTTT**.
- Al añadir la bombilla en la tarjeta `08`, deja vacía la IP local Tapo y pega las URLs en **Webhook ON** y **Webhook OFF**.
- Si una bombilla tiene Webhook configurado para la acción solicitada, `TapoSmartHomeClient` invoca el webhook HTTP/HTTPS (timeout 3 s) y refleja el estado `WEBHOOK OK` en pantalla.

---

## 5. Propuesta de Nuevos Datos y Widgets de Alto Valor para el Dashboard

Aprovechando que la tablet funciona como electrodoméstico doméstico siempre visible en España, estas son **6 ampliaciones de alto valor y coste cero (o mínimo)** que encajan de forma natural en la estética brutalista monocroma:

### 1. Precio de la Luz por Horas (PVPC / ESIOS REE)
- **Qué aporta**: curva monocroma de 24 horas del precio regulado PVPC (€/kWh) para España (Península/Canarias/Baleares), resaltando la **hora actual** y la **franja más barata del día** para programar lavadora, lavavajillas o carga de dispositivos.
- **Fuente**: API pública de **Red Eléctrica (REE / ESIOS)** o `api.preciodelaluz.org` (sin coste, actualización 1 vez al día a las 20:30 h cuando se publican los precios del día siguiente).

### 2. Calidad del Aire (AQI), Índice UV, Polen y Radar de Lluvia a 60 min
- **Qué aporta**: extensión directa del panel **`01 // CLIMA`** para corredores y entrenamientos al aire libre — índice europeo de calidad del aire (EAQI), picos de radiación UV, niveles de polen (gramíneas/olivo) y barras de precipitación minuto a minuto en la próxima hora.
- **Fuente**: **Open-Meteo Air Quality API** y **minutely_15 precipitation** (gratuito, sin API key y ya alineado con el proveedor meteorológico actual).

### 3. Resumen Diario de `semyra-nutrition` (Kcal, Proteína e Hidratación)
- **Qué aporta**: barra de progreso monocroma con calorías consumidas frente al objetivo diario, gramos de proteína restantes y vasos/litros de agua registrados, integrando nutrición y deporte en un mismo vistazo matutino y nocturno.
- **Fuente**: endpoint autenticado por token de solo lectura contra tu instancia de **`semyra-nutrition`** (Cloudflare Workers + D1).

### 4. Cuenta Atrás de Transporte Público Cercano (EMT / Cercanías / Metro)
- **Qué aporta**: complemento al panel **`02 // RUTA COCHE`** cuando decides moverte sin coche: próximos 2–3 autobuses o trenes en tu parada más cercana con cuenta atrás en minutos (`L27 · 4 min · 12 min`).
- **Fuente**: feeds públicos GTFS-RT / API abierta de movilidad (EMT Madrid OpenData, CRTM o API de tu ciudad).

### 5. Telemetría de Red Doméstica (Router, NAS, Pi-hole y Dispositivos LAN)
- **Qué aporta**: ampliación del monitor **`06 // ESTADO SEMYRA`** con latencia ICMP/TCP al router (`192.168.1.1`), estado de un servidor NAS/mini-PC doméstico, porcentaje de anuncios bloqueados en **Pi-hole / AdGuard Home** y alerta si cae la conectividad WAN.
- **Fuente**: sondas TCP locales en LAN + API REST local de Pi-hole / AdGuard (100% privado en red interna).

### 6. Recordatorios Domésticos Cíclicos y Cumpleaños (`.ics` + Bot de Telegram)
- **Qué aporta**: avisos recurrentes sin fricción (cambio de filtros, riego, ITV, mantenimiento de bici/zapatillas por kilómetros acumulados en Strava y cuenta atrás de cumpleaños próximos) integrados en **`07 // TAREAS & NOTAS`** con confirmación en un toque desde la tablet o desde Telegram.
- **Fuente**: feed `.ics` de cumpleaños + comandos ligeros en `TelegramBotService.kt` (p. ej. `/recurrente`, `/zapatillas`) y contador local de kilometraje de Strava.
