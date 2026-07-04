# Compatibilidad de dispositivos — ImuFlux

ImuFlux está diseñado para capturar IMU a **100 Hz sostenidos durante jornadas
completas (8–12 h)** con la pantalla apagada. Conseguirlo depende casi por
entero de lo agresiva que sea la capa de gestión de energía del fabricante
contra los *foreground services* y los *wake locks* parciales. El pipeline
interno (sensores → canal → CSV) está validado y nunca ha sido el cuello de
botella.

Este documento define qué dispositivos son aptos, cuáles marginales, y cuáles
no deben usarse. Está respaldado por:

- Evidencia empírica de sesiones reales validadas con `tools/validate_session.py`
  y `tools/check_data_quality.py`.
- Rankings públicos de [dontkillmyapp.com](https://dontkillmyapp.com/) sobre
  cómo cada OEM trata apps con actividad en background.
- El comportamiento conocido del framework Android respecto a `dataSync`
  foreground services, `PARTIAL_WAKE_LOCK` y `setExactAndAllowWhileIdle`.

> **Regla de oro**: un dispositivo **no se da por apto hasta haber pasado el
> "Test de compatibilidad"** integrado en la app (30 minutos con la pantalla
> bloqueada) **Y** una sesión real de ≥ 4 h con `validate_session.py`. Si no
> hay PASS en las dos cosas, no se usa para grabar sesiones largas.
>
> **Por qué no basta con el test de 30 min:** Android arranca cada app en el
> bucket `ACTIVE` al interactuar. Tarda **horas** (no minutos) en degradarla
> a `RARE` o `RESTRICTED`. Los killers agresivos de OxygenOS, ColorOS,
> FuntouchOS, MIUI y EMUI **sólo actúan cuando la app ha caído a esos
> buckets inferiores**. Por tanto, un test corto **no puede provocar un
> kill** incluso en los móviles que más tarde fallarán: el veredicto saldría
> PASS falso por construcción.
>
> **Cómo lo resuelve la app**: el analizador calcula un "veredicto bruto"
> basado sólo en métricas, y luego aplica un **techo por fabricante** para
> producir el veredicto final:
>
> | Fabricante | Techo | Racional |
> |---|---|---|
> | Google, Samsung, Sony, Nokia/HMD, Nothing, Fairphone | PASS | Respetan foreground services con onboarding estándar. |
> | Xiaomi/Redmi/POCO, Motorola, ASUS, Lenovo, desconocidos | **WARN** | Requieren validación con sesión real de ≥ 4 h para promocionar a PASS. |
> | OnePlus, OPPO, Realme, Vivo/iQOO, Huawei/Honor, Meizu | **FAIL** | Matan servicios tras horas de reposo; un test corto no lo detecta. Sólo 3 sesiones reales de ≥ 4 h con `watchdog_resurrections == 0` pueden anular el FAIL. |
>
> La duración mínima del test (30 min) tampoco es arbitraria: cubre el
> umbral de Doze light (~15–20 min) y asegura que las métricas brutas sean
> estadísticamente significativas.

---

## Contenido

1. [⚠️ Límite crítico de Android 15+ (dataSync 6 h)](#️-límite-crítico-de-android-15-datasync-6-h)
2. [Tier A — Recomendados (mid-range)](#tier-a--recomendados-mid-range)
3. [Tier B — Marginal con ajustes estrictos](#tier-b--marginal-con-ajustes-estrictos)
4. [Tier C — NO recomendados](#tier-c--no-recomendados)
5. [Requisitos hardware mínimos](#requisitos-hardware-mínimos)
6. [Ajustes obligatorios (cualquier dispositivo)](#ajustes-obligatorios-cualquier-dispositivo)
7. [Protocolo para cualificar un modelo nuevo](#protocolo-para-cualificar-un-modelo-nuevo)
8. [Cómo diagnosticar una sesión fallida](#cómo-diagnosticar-una-sesión-fallida)
9. [Consumo de batería esperado](#consumo-de-batería-esperado)
10. [Historial de dispositivos probados](#historial-de-dispositivos-probados)

---

## ✅ Límite de Android 15+ (`dataSync` 6 h) — RESUELTO

**Este límite afectaba a TODOS los dispositivos, incluidos los Pixel.** Era
ortogonal al kill del OEM y se ha resuelto a nivel de app.

Desde **Android 15** (API 35), Google impone un **tope de 6 h acumuladas en
cualquier ventana de 24 h** a los foreground services declarados con
`foregroundServiceType="dataSync"`. Al llegar al tope el sistema llama a
`Service.onTimeout()`; si la app no hace `stopSelf()` en unos segundos, se
lanza `RemoteServiceException` y el proceso muere.

### Migración implementada (abril 2026)

ImuFlux ha migrado de `dataSync` a `specialUse` siguiendo la estrategia
recomendada para uso industrial:

**`AndroidManifest.xml`**
- Permiso añadido: `FOREGROUND_SERVICE_SPECIAL_USE`
- Tipo declarado: `android:foregroundServiceType="dataSync|specialUse"`
- Justificación incluida en `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` (revisada por Google Play)

**`RecordingService.kt`**
- Android 14+ (API 34+): `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` → sin límite de duración
- Android 10-13 (API 29-33): `startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` → sin límite en esas versiones
- Android < 10: llamada sin tipo (comportamiento anterior)

Con esta migración los dispositivos Tier A pueden garantizar sesiones de **12 h
o más** en Android 15+ sin ningún corte forzoso por el sistema.

---

## Tier A — Recomendados (mid-range)

Dispositivos donde, tras el onboarding estándar, el pipeline completa sesiones
largas sin huecos, con `dt_median ≈ 10.0 ms` y `gaps = 0`. Todos los modelos
listados están en rango mid-range (200–550 €), disponibles en UE a 04/2026,
con el set completo de sensores IMU requerido (accel + gyro + magnet +
rotation vector).

### Preferentes (mejor relación fiabilidad/precio)

| Modelo | PVP aprox. (2026) | Android | OS | Notas |
|---|---|---|---|---|
| **Google Pixel 9a** | ~500 € | 15 → 16+ | Stock | **Recomendado #1.** 7 años de updates garantizados hasta 2031. Capa OS mínima, foreground services respetados sin intervención manual. |
| **Google Pixel 8a** | ~400 € (nuevo) / ~300 € (usado) | 14 → 16+ | Stock | Alternativa más barata al 9a. Actualizaciones hasta 2031. |
| **Google Pixel 7a** | ~250–300 € (usado) | 13 → 15 | Stock | Discontinuado oficialmente, pero ampliamente disponible en segunda mano. Updates hasta 2028. |
| **Nothing Phone (3a)** | ~350 € | 15 | Nothing OS (near-stock) | Muy cercano a AOSP. Reportes comunitarios excelentes para background services. 3 años de Android + 6 de security. |
| **Nothing Phone (2a) Plus** | ~300 € | 14 → 16 | Nothing OS | Alternativa más barata. |
| **Sony Xperia 10 VI** | ~400 € | 14 → 16 | Near-stock | Capa muy cercana a stock. Stamina Mode debe estar desactivado para ImuFlux. Batería de 5000 mAh. |

### Samsung (con matices)

Samsung respeta foreground services **si se configuran correctamente**, pero
One UI 7 (Android 15) introdujo regresiones puntuales que obligan a
revalidar tras cada actualización mayor. Pasan a Tier A sólo **después** de
una sesión real de ≥ 4 h validada en el OS actualmente instalado.

| Modelo | PVP aprox. (2026) | Android | OS | Notas |
|---|---|---|---|---|
| **Samsung Galaxy A56 5G** | ~450 € | 15 | One UI 7 | Sensor suite completa. Procesador Exynos 1580. Bateria 5000 mAh. |
| **Samsung Galaxy A36 5G** | ~350 € | 15 | One UI 7 | Alternativa más económica; mismo comportamiento OS. |
| **Samsung Galaxy A55 5G** | ~380 € | 14 → 15 | One UI 6 / 7 | Verificado en producción en el proyecto. Revalidar tras update a OneUI 7. |
| **Samsung Galaxy A35 5G** | ~280 € | 14 → 15 | One UI 6 / 7 | El más económico de la serie A con sensor suite completa. |

**Importante (Samsung)**: tras cada update mayor del OS hay que:
- Confirmar que la app sigue en "Apps que no se duermen" (a veces se resetea).
- Confirmar que no aparece en "Poner en reposo apps no usadas".
- Reaplicar "Sin restricciones" en batería.
- Volver a pasar el test de compatibilidad interno.

**Regresión conocida One UI (jul 2026, Galaxy A35)**: con la pantalla apagada,
el sensor hub de One UI recorta la frecuencia de entrega a la mitad (100 Hz
solicitados → ~50 Hz reales, `dt` uniforme de ~20 ms) y, en Doze profundo,
llega a suspender por completo la entrega durante minutos u horas **con el
proceso vivo** (el `foreground service` y el heartbeat siguen activos, así que
el watchdog de proceso no lo detecta). Mitigaciones implementadas en la app
(ver [Contramedidas de muestreo](#contramedidas-de-muestreo-one-ui--doze)):
sobre-muestreo + resampleo de rejilla, sensores wake-up, hilo dedicado,
watchdog de stall, telemetría de tasa cruda y panel de ajustes. **Requiere
revalidar el A35 con estas mitigaciones antes de confirmarlo en Tier A.**

### Criterio de entrada al Tier A

Un modelo nuevo entra en Tier A sólo si:

1. `Build.MANUFACTURER` está en la lista `RELIABLE` del `ManufacturerReliability.kt`.
2. Test de compatibilidad → veredicto PASS (requiere fabricante RELIABLE).
3. Sesión real de ≥ 4 h con `completeness ≥ 0.99`, `gaps == 0` y
   `watchdog_resurrections == 0` en el `metadata.json`.

## Tier B — Marginal con ajustes estrictos

Funcionan si y sólo si el usuario aplica **todos** los ajustes del onboarding
específico del fabricante. El techo del test es WARN; para promocionar a PASS
de facto, exigir dos sesiones reales consecutivas limpias.

### Xiaomi / Redmi / POCO

HyperOS 2 (sucesor de MIUI 14) ha mejorado notablemente respecto a MIUI 12/13
si se aplica toda la configuración. Caso validado en el proyecto: **POCO X4
Pro** supera el test corto de 30 min tras activar Autostart y "Sin
restricciones".

| Modelo | PVP aprox. (2026) | Notas |
|---|---|---|
| **Xiaomi Redmi Note 14 Pro** | ~300 € | Sensor suite completa. Dimensity 7300 Ultra. |
| **Xiaomi Redmi Note 13 Pro** | ~250 € (usado ~200 €) | Muy disponible en segunda mano. |
| **POCO X7 Pro** | ~350 € | Hermano del Redmi Note 14 Pro con énfasis en rendimiento. |
| **POCO X6 Pro** | ~270 € | Caso análogo al POCO X4 Pro del proyecto. |
| **POCO M6 / M7 Pro** | ~200–250 € | Gama más económica; verificar que tiene giroscopio (algunos M-series lo omiten). |
| **Xiaomi 14 Lite / 14T** | ~400–450 € | Gama superior sin llegar a flagship. |

**Ajustes obligatorios** (Xiaomi/POCO/Redmi):
- **Autostart** activado en Seguridad → Permisos → Autostart.
- **Sin restricciones** en Ajustes → Apps → ImuFlux → Batería.
- **Candado en recientes** (deslizar hacia abajo en la tarjeta de la app).
- **Desactivar Memory extension** (afecta al OOM killer).
- **Desactivar MIUI Optimization** en opciones de desarrollador (opcional pero recomendado).

### Motorola

Moto G/Edge es capa casi-stock, pero el "Ahorro de batería adaptativo" puede
afectar. Comportamiento mixto según modelo.

| Modelo | PVP aprox. (2026) | Notas |
|---|---|---|
| **Motorola Moto G85 5G** | ~250 € | Sensor suite completa. |
| **Motorola Moto G Power 2024** | ~230 € | Batería 5000 mAh, buena para jornadas largas. |
| **Motorola Edge 50 Fusion** | ~350 € | Gama superior del ecosistema Moto G. |

### ASUS (Zenfone/ROG)

ZenUI es casi stock pero incluye "PowerMaster" que puede interferir. Modelos
gaming (ROG) tienen menos restricciones por diseño.

| Modelo | PVP aprox. (2026) | Notas |
|---|---|---|
| **ASUS Zenfone 11** (no Ultra) | ~550 € | Límite superior del rango mid-range. |
| **ASUS ROG Phone 7** (usado) | ~400 € | Enfocado gaming; su capa no mata background apps. |

### Criterio de entrada al Tier B

- Fabricante CONDITIONAL en `ManufacturerReliability.kt`.
- Test de compatibilidad → veredicto WARN (fabricante capado).
- **Dos** sesiones reales consecutivas de ≥ 4 h con los criterios de validación
  estrictos.

## Tier C — NO recomendados

Dispositivos donde el OS mata el foreground service pese a `PARTIAL_WAKE_LOCK`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` concedido y `foregroundServiceType=specialUse`.
**No usar para grabar sesiones largas.** Aparecen repetidamente kills y gaps
≥ 10 minutos (la firma típica: el watchdog `AlarmManager` tarda en media
`INTERVAL_MS` en resucitar el servicio).

El kill en estos OEMs ocurre **tras horas de reposo** (cuando la app cae al
bucket `RARE`/`RESTRICTED` del App Standby). Por eso un test corto de 30 min
no lo detecta — la app sigue en `ACTIVE`. Estas familias están hardcodeadas
como `HOSTILE` en `ManufacturerReliability.kt` y el test interno **nunca** les
dará PASS, por mucho que las métricas brutas salgan limpias.

| Familia | Marcas/modelos representativos | OS | Por qué |
|---|---|---|---|
| **OnePlus** | Nord 2T 5G *(caso de referencia)*, Nord 3, Nord CE, 12, 13 | OxygenOS 12+ (basado en ColorOS) | Caso documentado: Nord 2T 5G CPH2399 → 17 h de grabación → 8.3% de filas esperadas, 329 gaps, `max_gap = 10 min 0.7 s`. |
| **OPPO** | A-series, Reno, Find | ColorOS 12+ | Mismo motor que OxygenOS desde la fusión de 2021. |
| **Realme** | GT, Narzo, Número | Realme UI (ColorOS) | Mismo motor que OxygenOS. |
| **Vivo / iQOO** | Y-series, V-series, X-series, iQOO Neo | FuntouchOS / OriginOS | Kills agresivos incluso con permisos concedidos. |
| **Huawei / Honor** | P-series, Mate, Nova; Honor Magic, X-series | EMUI 11+ / HarmonyOS / MagicOS | Kills agresivos + servicios Google limitados (afecta a notificaciones y reinicio de servicios). |
| **Meizu** | 21, 20 | Flyme | Flyme mata foreground services tras inactividad. |

> **Nota sobre el ecosistema BBK (OnePlus/OPPO/Realme/Vivo/iQOO)**: las cinco
> marcas comparten el grupo empresarial BBK Electronics y su stack de OS está
> unificado en la base ColorOS/OxygenOS/FuntouchOS. Los ajustes documentados
> ("Permitir actividad en segundo plano", "Gestor de arranque", "Apps
> protegidas") reducen los kills pero no los eliminan. Para jornadas completas
> a 100 Hz sostenidos el margen de incertidumbre es inaceptable.

> **Nota sobre Huawei/Honor en Europa**: los modelos comercializados en UE desde
> 2024 (P60 Pro, Magic 7, etc.) pueden funcionar con servicios Google vía App
> Gallery + MicroG, pero el comportamiento del OEM killer sigue siendo hostil.

### Cómo anular el FAIL en un dispositivo HOSTILE

El test interno siempre dará FAIL para estos fabricantes. Si un modelo
concreto demuestra empíricamente no sufrir el kill, se puede reclasificar
editando `ManufacturerReliability.kt` (mover a `CONDITIONAL`) tras:

1. **Tres** sesiones reales consecutivas de ≥ 4 h, en días distintos.
2. `completeness ≥ 0.99`, `gaps == 0`, `watchdog_resurrections == 0` en las
   tres.
3. Anotación en la tabla "Historial de dispositivos probados" con Android,
   versión del OEM OS y fecha exacta.

Una sola sesión limpia **no** basta — los killers de estos OEMs son
estocásticos y pueden saltarse una sesión y actuar en la siguiente.

---

## Contramedidas de muestreo (One UI / Doze)

A raíz de la regresión del Galaxy A35 se añadieron cuatro mecanismos en el
pipeline de captura. Todos son ajustables desde `RecordingTuningStore`
(opciones de desarrollador); los defaults dependen del fabricante.

1. **Sobre-muestreo + resampleo de rejilla** (`RecordingTuningStore`, aplicado
   en `SensorHub` vía `GridResampler`). En Samsung se solicitan **200 Hz**
   (`5_000` µs) y se **resamplea a una rejilla de 100 Hz**: se emite un frame
   por punto de rejilla, etiquetado con el **timestamp sintetizado de la
   rejilla** (no el del evento), avanzando en pasos fijos anclados a tiempo
   absoluto. Esto sustituye a la antigua decimación por periodo mínimo, que
   ante una entrada de ~122 Hz descartaba una muestra de cada dos y partía la
   salida a la mitad (~61 Hz). Con timestamps sintetizados, cualquier entrada
   limpia ≥ objetivo produce `dt` exactamente igual al periodo (10,00 ms) y
   jitter ~0 — evita el `dt` bimodal (8/16 ms) que daba un jitter de ~6,5 ms
   pese a grabar 100 Hz de media. El resampler se re-ancla al timestamp real en
   el arranque y tras un hueco (el hueco sigue siendo visible; no se rellena
   con muestras obsoletas); por debajo del objetivo la salida sigue a la
   entrada sin fabricar frecuencia. Cubierto por `GridResamplerTest`. En el
   resto de fabricantes se piden 100 Hz sin resampleo.
2. **Sensores wake-up** (`SensorManager.getDefaultSensor(type, true)` con
   fallback a non-wakeup). Despiertan el SoC para vaciar su FIFO antes de que
   desborde, atacando los huecos de minutos en Doze.
3. **HandlerThread dedicado de alta prioridad** para los callbacks de sensores
   (antes iban al main looper). Evita que la UI/SDK de terceros retrasen o
   coalescan la entrega (el patrón de saltos de ~60 ms al arrancar).
4. **Watchdog de stall** (`RecordingEngine`): si no llegan frames durante 5 s
   con la grabación activa, re-registra los listeners y cuenta `sensor_restarts`
   en `metadata.json`. Cubre el caso que el watchdog de proceso no ve (proceso
   vivo pero sensor hub detenido).
5. **Telemetría de tasa cruda**: `SensorHub` cuenta los eventos crudos del
   sensor maestro *antes* del resampleo. `RecordingHealth.rawSamplesPerSecond`
   se muestra en la notificación como **"sensor X Hz → escrito Y Hz"** y se
   persiste como `last_raw_hz` en el heartbeat. Permite distinguir un cap de
   firmware (crudo ~60 Hz) de un artefacto de resampleo (crudo ~120 Hz mal
   downsampleado) sin tener Logcat conectado.
6. **Panel de ajustes de muestreo** (Test de compatibilidad → "Ajustes de
   muestreo (avanzado)"): permite iterar wakeup on/off, frecuencia solicitada
   (100/200/400 Hz), batching on/off y resampleo on/off **sin recompilar**. El
   preset de 400 Hz usa el permiso `HIGH_SAMPLING_RATE_SENSORS`.

Además, la app **bloquea el inicio de grabación** si la exención de
optimización de batería no está concedida, y registra en `metadata.json` el
estado de energía al arrancar (`power_state_at_start`) y por heartbeat
(`last_power_state`, `last_effective_hz`, `last_raw_hz`, `sensor_restarts`)
para poder correlacionar caídas de tasa con Doze/pantalla/carga al analizar la
sesión. Los descriptores de sensores incluyen ahora la variante realmente
resuelta (`is_wake_up`, `max_delay_us`).

### Protocolo de revalidación (comparar configuraciones)

Para un modelo con sospecha de throttling (como el A35), usar el **panel de
ajustes de muestreo** de la pantalla de test para iterar configuraciones y
ejecutar tests cortos con pantalla apagada y **sin cargador**. El objetivo de
la Fase 1 es **leer la tasa cruda** ("sensor X Hz" en la notificación /
`last_raw_hz` en `metadata.json`) para decidir la ruta:

1. Test corto (~10 min, pantalla apagada) con la config actual y mirar la
   notificación **"sensor X Hz → escrito Y Hz"**:
   - **Crudo ~120–200 Hz** → el resampleo de rejilla lo resuelve; repetir el
     test de 30 min y validar.
   - **Crudo ~60 Hz** → desactivar wake-up en el panel (el watchdog de stall +
     la exención de batería cubren los huecos) y repetir; si sube a ≥ 100 Hz,
     quedarse con non-wakeup.
   - **Sigue ~60 Hz con non-wakeup** → probar batching off y el preset de
     **400 Hz**. Si persiste, es un cap de firmware de One UI con la pantalla
     apagada y hay que evaluarlo como limitación del modelo.
2. Configuraciones de referencia:

| Config | samplingPeriodUs | decimateToHz | maxReportLatencyUs | wakeup |
|---|---|---|---|---|
| A — wakeup + hilo, 100 Hz | 10 000 | 0 | 200 000 | sí |
| B — sobre-muestreo 200→100 Hz | 5 000 | 100 | 200 000 | sí |
| C — sin batching | 5 000 | 100 | 0 | sí |
| D — non-wakeup 200→100 Hz | 5 000 | 100 | 200 000 | no |
| E — alta tasa 400→100 Hz | 2 500 | 100 | 0 | según prueba |

Con la config ganadora (la que dé `dt_median ∈ [9.5, 10.5]`, `gaps == 0`,
`completeness ≥ 99 %` en `tools/validate_session.py --strict`), ejecutar una
**sesión real de ≥ 4 h** con pantalla apagada y actualizar el historial de
esta tabla con la config usada y `last_raw_hz` observado.

## Requisitos hardware mínimos

El dispositivo debe exponer **todos** los siguientes sensores vía
`SensorManager.getDefaultSensor`:

- `TYPE_ACCELEROMETER` (obligatorio).
- `TYPE_LINEAR_ACCELERATION` (obligatorio).
- `TYPE_GRAVITY` (obligatorio).
- `TYPE_GYROSCOPE` (obligatorio).
- `TYPE_ROTATION_VECTOR` (obligatorio).
- `TYPE_MAGNETIC_FIELD` (obligatorio).

Además, para que el ahorro de batería sea significativo, el acelerómetro y el
giroscopio deberían exponer `fifoMaxEventCount > 0` (batching hardware). La
app hace *fallback* automático a la llamada sin batching si no lo soportan,
pero el consumo sube ~30–50 %.

El `metadata.json` de cada sesión incluye el descriptor completo de los
sensores realmente resueltos (`vendor`, `resolution`, `fifo_max_event_count`,
`min_delay_us`, `max_delay_us`, `is_wake_up`) — consúltalo antes de validar un
modelo nuevo. `max_delay_us` e `is_wake_up` ayudan a distinguir un cap de
firmware (p.ej. wake-up limitado a ~60 Hz) de un problema de configuración.

Otras condiciones:

- Android 10 (API 29) o superior (requerido por `foregroundServiceType`).
- ≥ 1 GB libre en almacenamiento interno (chequeo explícito en `RecordingEngine`).
- Batería ≥ 80 % al inicio de una sesión de 8 h (medida conservadora frente a
  *thermal throttling*).

---

## Ajustes obligatorios (cualquier dispositivo)

Incluso en Tier A, el usuario debe completar **una vez por instalación**:

1. Aceptar el diálogo "Ignorar optimización de batería" al abrir la app.
2. Aceptar el diálogo de fabricante y aplicar los pasos específicos.
3. Conceder el permiso `POST_NOTIFICATIONS` (Android 13+).
4. Verificar en Ajustes → Apps → ImuFlux → Batería que aparece como **"Sin
   restricciones"**.
5. Ejecutar el **Test de compatibilidad** (30 min, pantalla apagada, sin carga).

En móviles que no sean de Tier A, además:

6. Añadir la app a "Apps que no se duermen" / "Autostart" / "Protegidas".
7. Desactivar "Ahorro de batería adaptativo" si el OEM lo tiene.
8. Mantener la app pineada/candada en la pantalla de recientes.

---

## Protocolo para cualificar un modelo nuevo

Antes de adoptar un dispositivo desconocido:

1. Instalar la build **release** (no debug) de ImuFlux.
2. Aplicar los ajustes de la sección anterior.
3. Ejecutar el **Test de compatibilidad** desde la app (menú → "Test de
   compatibilidad"). Duración: **30 min**, pantalla apagada, sin tocar el
   teléfono y **sin cable de carga** (la carga relaja las políticas de kill
   del OEM en algunos fabricantes y puede producir falsos PASS).
4. Comprobar el veredicto **y el veredicto bruto** (la pantalla muestra los
   dos si difieren). El techo por fabricante limita el veredicto final:
   - **PASS** → dispositivo apto en su estado actual (sólo posible en
     fabricantes FIABLE: Pixel, Samsung, Sony, Nokia/HMD, Nothing, Fairphone).
   - **WARN** → métricas correctas pero hace falta validar con sesión larga
     (fabricantes CONDICIONAL o DESCONOCIDO).
   - **FAIL** → o bien métricas malas, o bien el fabricante es HOSTIL
     (OnePlus, OPPO, Realme, Vivo, Huawei/Honor, Meizu) y el test corto no
     basta para anular ese cap.
5. **La sesión real es obligatoria** siempre que el fabricante no sea
   FIABLE. Ejecutar ≥ 4 h con pantalla apagada, sin tocar el teléfono y sin
   cargador (usar `tools/validate_session.py --strict` sobre el ZIP
   exportado). Criterios de aceptación:
   - `dt_median` ∈ [9.5, 10.5] ms.
   - `gaps` (> 50 ms) == 0.
   - `jitter_p95` < 5 ms.
   - `completeness = rows / (duration_s * 100) ≥ 0.99`.
   - `watchdog_resurrections` == 0 en `metadata.json`.

   Para anular el FAIL automático en un fabricante HOSTIL son necesarias
   **3 sesiones reales consecutivas** que cumplan los 5 criterios, en días
   distintos. Una sola sesión limpia no basta porque los killers de estos
   OEMs son estocásticos.
6. Si los 6 criterios se cumplen, añadir el modelo al historial de esta tabla
   con fecha, versión Android y versión ImuFlux.

---

## Cómo diagnosticar una sesión fallida

Antes de descartar un dispositivo, ejecuta estos scripts sobre el CSV o el ZIP
exportado:

```bash
python3 tools/validate_session.py /ruta/sesion.zip --strict
python3 tools/check_data_quality.py /ruta/sesion.zip --verbose
```

Interpretación rápida de los síntomas:

| Síntoma | Causa típica |
|---|---|
| `dt_median` ≈ 10 ms pero `gaps > 0` y `max_gap ≈ N · INTERVAL_MS` del watchdog | **Kill del OEM**: el OS suspende el proceso y el watchdog lo resucita cada `INTERVAL_MS`. Dispositivo de Tier C. |
| `dt_median > 10.5 ms` sin gaps | El sensor está limitado por hardware a menos de 100 Hz. No es problema de la app. |
| `dt_median` ≈ 10 ms pero `jitter_p95 > 5 ms` | Contención de CPU o GC. Revisar otras apps corriendo. |
| `completeness ≈ 1.0` pero `watchdog_resurrections > 0` | El service murió al menos una vez, pero el auto-resume funcionó limpio. Tier B aceptable. |
| `rows ≈ 0` o sesión muy corta | Fallo de permisos, disco lleno o kill temprano. Revisar Logcat filtrando `RecordingService`. |

El caso documentado (OnePlus Nord 2T 5G CPH2399, 17h16m de grabación):

```
rows              =       516547
duration          =     62161.74 s  (~8.3% de las 6.2M filas esperadas a 100 Hz)
dt_median_ms      =       9.8732   ← pipeline perfecto cuando la CPU está activa
jitter_p95_ms     =       0.2118   ← excelente
gaps (>50 ms)     =          329
max_gap_ms        =  600659.1357   ← 10 min clavados = INTERVAL_MS del watchdog
```

Lectura: **el pipeline captura a 100 Hz exactos cuando está vivo; OxygenOS
lo mata cada pocos minutos**. Dispositivo Tier C.

---

## Consumo de batería esperado

Mediciones orientativas con `samplingPeriodUs = 10_000` y
`maxReportLatencyUs = 200_000` (batching HW de ~20 muestras por ráfaga),
pantalla apagada, sin otras apps activas, sensor hub hardware operativo:

| Dispositivo | Batería nominal | Consumo observado | Autonomía teórica @100 Hz |
|---|---|---|---|
| Pixel 9a | 5100 mAh | ~3 %/h | ~30 h |
| Pixel 8a | 4500 mAh | ~3.5 %/h | ~28 h |
| Pixel 7a | 4385 mAh | ~4 %/h | ~25 h |
| Samsung Galaxy A56 | 5000 mAh | ~4 %/h | ~25 h |
| Samsung Galaxy A55 | 5000 mAh | ~4.5 %/h | ~22 h |
| Samsung Galaxy S22 | 3700 mAh | ~5 %/h | ~20 h |
| Nothing Phone (3a) | 5000 mAh | ~4 %/h | ~25 h |
| Sony Xperia 10 VI | 5000 mAh | ~4 %/h | ~25 h |
| POCO X6/X7 Pro | 5000 mAh | ~4–5 %/h | ~20–25 h |

En la práctica, con tira de pantalla puntual y otras apps en background
(mensajería, correo), la autonomía real suele ser **60–75 %** de la teórica.
Esto deja margen cómodo para 8–12 h sin cargador en cualquier dispositivo
Tier A de esta tabla.

Si el consumo observado en tu dispositivo de Tier A excede el 7 %/h, revisa:

- Que el sensor esté reportando con batching (log de `SensorHub`: "OK con
  batching"). Sin batching el consumo sube un 30–50 %.
- Que no haya otra app (GPS, Bluetooth, red) activa en paralelo.
- Que la pantalla esté realmente apagada (algunos OEMs la mantienen en AOD).
- Que el thermal throttling no haya degradado la CPU (sesiones largas en
  verano pueden subir temperatura y activar limitación).

No se han identificado optimizaciones adicionales que reduzcan más el consumo
sin sacrificar sample rate.

---

## Historial de dispositivos probados

Actualiza esta tabla cada vez que cualifiques un dispositivo nuevo.

| Modelo (`Build.MODEL`) | Fabricante | Android | ImuFlux | Tier | Sesión de referencia | Observaciones |
|---|---|---|---|---|---|---|
| CPH2399 (OnePlus Nord 2T 5G) | OnePlus | 13 | v1 | **C** | 17h16m → 8.3 % rows, 329 gaps, `max_gap = 10 min` | Kills reiterados de OxygenOS. No usar. Caso de referencia del proyecto. |
| 2201116PG (POCO X4 Pro 5G) | Xiaomi | — | v1 | **B** (pendiente sesión larga) | Test de 30 min → WARN (capado por fabricante CONDITIONAL) con métricas limpias | Con Autostart + "Sin restricciones" superó el test corto. Falta sesión real de ≥ 4 h para confirmar. |
| SM-A356B (Galaxy A35 5G) | samsung | 15 (One UI 7) | v1 | **revalidar** | (1) 7,95 h → **30,6 % rows**, ~50 Hz, 3 huecos (≈ 3 h muertas). (2) Contramedidas + exención: 30 min → **0 huecos** pero 60,8 % rows, ~61 Hz. (3) Resampleo de rejilla: 30 min → **100 % rows, 0 huecos, 180 015 filas (100 Hz de media)** pero mediana 8,25 ms / jitter 6,45 ms → FAIL | Huecos resueltos y tasa cruda confirmada en ~121 Hz (HW sano). El FAIL (3) era por emitir el timestamp **real** del evento (dt bimodal 8/16 ms); corregido emitiendo [timestamps de rejilla sintetizados](#contramedidas-de-muestreo-one-ui--doze) (dt = 10 ms exactos). **Pendiente**: re-test de 30 min con el fix (config 200 Hz, rejilla ON) → APTO esperado → sesión de ≥ 4 h antes de confirmar Tier A. |
| _(añade aquí los demás dispositivos validados)_ | | | | | | |

