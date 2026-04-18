# ImuFlux — Arquitectura

> Documento de referencia para el desarrollo de la app. Cualquier cambio significativo
> debe **primero** validarse contra las reglas de este documento. El objetivo principal
> de la app es grabar señales IMU a **100 Hz constantes durante jornadas de hasta 8 h**
> sin huecos temporales, sin crashes por memoria y con el menor consumo de batería
> posible, incluso con la pantalla bloqueada.

---

## 1. Principios de diseño

1. **Clean Architecture** con flujo unidireccional de datos: `ui → viewmodel → usecase → repository → data source / hardware`. Las capas inferiores nunca conocen las superiores.
2. **Separar captura de almacenamiento**. Productor (hilo de sensores) y consumidor (hilo de I/O) se comunican por un canal acotado; si el consumidor se atasca, el productor **no se bloquea** (política `DROP_OLDEST` con contador de pérdidas).
3. **Cero allocations en el hot path**. El camino `SensorEvent → línea de CSV` reutiliza `FloatArray` y `StringBuilder` para evitar GC a 100 Hz.
4. **Streaming a disco, nunca en RAM**. El buffer "grabar todo en una lista y volcar al final" es incompatible con 8 h — prohibido. Se escribe continuamente en chunks.
5. **Timestamp autoritativo = `SensorEvent.timestamp` (ns monotónicos desde boot)**. Nunca `System.currentTimeMillis()` para marcar muestras; sólo se usa como campo auxiliar (`timestamp_ms`) en la cabecera del chunk.
6. **Un único registro de sensores por proceso** (SensorHub singleton con hot flow compartido). Prohibido tener dos `callbackFlow` cold registrando el mismo sensor.
7. **La UI no se recompone a 100 Hz**. Los flujos a UI van throttled a ~10 Hz.

---

## 2. Namespace y organización de paquetes

Raíz: `com.example.scantest` (se mantiene por compatibilidad con Gradle, Hilt y Manifest; el nombre histórico del paquete no justifica la invasividad de renombrar todo). Se puede migrar a `com.imuflux.app` en una iteración futura dedicada.

```
com.example.scantest
├── di/                         # Módulos Hilt
├── domain/
│   ├── model/                  # Entidades puras (Kotlin, sin deps Android)
│   │   ├── SensorType.kt
│   │   ├── SensorFrame.kt
│   │   ├── SensorSnapshot.kt
│   │   ├── CustomMovement.kt
│   │   ├── DetectionLog.kt
│   │   ├── RecordingHealth.kt
│   │   └── SessionMetadata.kt
│   ├── repository/             # Interfaces de repositorio
│   └── usecase/                # Un caso de uso por acción
├── data/
│   ├── sensors/                # Captura en tiempo real
│   │   ├── SensorHub.kt        # Singleton, HandlerThread URGENT_AUDIO
│   │   └── FrameAssembler.kt   # hold-last-sample, 100 Hz
│   ├── storage/                # Persistencia
│   │   ├── SessionFileManager.kt
│   │   ├── CsvChunkWriter.kt
│   │   └── SessionIndex.kt
│   └── repository/             # *RepositoryImpl (bindings en AppModule)
├── recording/                  # Orquestación
│   ├── RecordingEngine.kt      # Productor ↔ Channel ↔ Consumidor
│   └── RecordingHealthTracker.kt
├── service/
│   └── RecordingService.kt     # Foreground `dataSync` + WakeLock
└── ui/
    ├── screen/                 # Pantallas Compose
    ├── viewmodel/
    └── theme/
```

**Reglas de dependencia** (se verifican manualmente; enforcement con Gradle modules puede venir en una fase posterior):

- `domain/` no importa nada de `data/`, `service/`, `ui/` ni `android.*`. Excepción práctica: `SensorFrame` contiene primitivos.
- `data/` no importa nada de `ui/` ni `service/`.
- `service/` puede importar `data/` y `recording/`, nunca `ui/`.
- `ui/` sólo importa `domain/` (directamente) y `data/` a través de repositorios inyectados por Hilt.

---

## 3. Threading

| Componente                | Hilo / Dispatcher                                                       | Justificación                                                                                 |
| ------------------------- | ----------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `SensorEventListener`     | `HandlerThread("ImuSensorThread", THREAD_PRIORITY_URGENT_AUDIO)`        | Prioridad alta evita jitter. Un único thread centraliza todos los sensores.                   |
| `FrameAssembler.emit()`   | Mismo HandlerThread de sensores                                         | Cero saltos de hilo en el hot path.                                                           |
| `RecordingEngine` (consumidor) | `Dispatchers.IO.limitedParallelism(1)`                            | Un solo hilo de I/O → escritura secuencial, sin locks y sin contender con otros I/O.          |
| Compose / ViewModel       | `viewModelScope` (Main)                                                 | Sólo consume flujos ya throttled a 10 Hz.                                                      |
| Evaluación de movimientos | `Dispatchers.Default`                                                   | CPU bound, ligero; se ejecuta sobre el flujo throttled, no a 100 Hz.                           |

Prohibido: `synchronized` en el callback del sensor (ya se eliminó en la refactorización). La sincronización ocurre vía canales coroutine.

---

## 4. Pipeline de datos

```
┌──────────────────┐      event.timestamp (ns)      ┌───────────────┐
│  SensorManager   │ ──────────────────────────────▶│   SensorHub   │
│  (HW + FIFO)     │        batched ≈200 ms         │ (singleton)   │
└──────────────────┘                                └───────┬───────┘
                                                           │ onSensorEvent (HandlerThread)
                                                           ▼
                                                  ┌────────────────────┐
                                                  │  FrameAssembler    │
                                                  │  hold-last-sample  │
                                                  │  slots FloatArray  │
                                                  └──────┬─────────────┘
                                                         │ un SensorFrame por cada
                                                         │ evento de ACCELEROMETER
                                                         ▼
                                          ┌───────────────────────────────┐
                                          │  Channel<SensorFrame>         │
                                          │  capacity=2048                │
                                          │  onBufferOverflow=DROP_OLDEST │
                                          └──────────────┬────────────────┘
                                                         │ Dispatchers.IO (1 hilo)
                                                         ▼
                                          ┌───────────────────────────────┐
                                          │   CsvChunkWriter              │
                                          │   rotate cada 5 min / 20 MB   │
                                          │   flush cada 1 s              │
                                          └──────────────┬────────────────┘
                                                         ▼
                              files/sessions/<sid>/chunk_000.csv, chunk_001.csv, ...
                              files/sessions/<sid>/metadata.json
```

Rama paralela para UI:

```
SensorHub  ──▶  lastFrameFlow (StateFlow throttled a 10 Hz)  ──▶  ViewModel  ──▶  Compose
```

---

## 5. Frecuencia 100 Hz constante — reglas operativas

1. **Registro con batching hardware**:
   ```kotlin
   sensorManager.registerListener(
       listener,
       sensor,
       samplingPeriodUs = 10_000,        // 100 Hz nominal
       maxReportLatencyUs = 200_000,     // 200 ms de batching → SoC duerme entre lotes
       handler = sensorHandler
   )
   ```
2. El acelerómetro (preferentemente `TYPE_LINEAR_ACCELERATION`, fallback `TYPE_ACCELEROMETER`) es el **reloj maestro**: cada evento suyo produce exactamente una fila.
3. Los demás sensores actualizan su slot en el `FrameAssembler` cuando llegan; al emitir frame se usa el último valor conocido (hold-last-sample). En el CSV de una pipeline post-proceso esto se traduce en "valor válido durante un intervalo".
4. Si un sensor opcional no existe en el dispositivo (p.ej. sin magnetómetro), sus columnas se escriben como campos vacíos (`,,,` en CSV) — el frame no se rompe.
5. Se **prohíbe** cualquier ticker externo que resamplee a una frecuencia fija leyendo una variable compartida. El hardware marca el ritmo; si el driver entrega a 98 o 103 Hz, eso es lo que grabamos (el análisis offline interpola si hace falta). Lo que NO hacemos es inventar muestras.

### Calidad medible en vivo (`RecordingHealthTracker`)

Se expone un `StateFlow<RecordingHealth>` con:

- `samplesPerSecond` (ventana móvil 10 s).
- `jitterP95Ns` (desviación del intervalo entre eventos respecto al nominal 10 ms).
- `framesQueued` y `framesDropped` (del canal productor/consumidor).
- `bytesWritten` y `currentChunkIndex`.

La UI muestra estos valores en modo compacto. Si `framesDropped > 0` o `jitterP95Ns > 5 ms` → icono de advertencia.

---

## 6. Formato del CSV (wide)

Un solo formato, una fila por frame. Cabecera en **todos** los chunks (cada chunk es autocontenido, robustez > compacidad).

Columnas (en este orden, nombres fijos):

```
timestamp_ns, timestamp_ms,
acc_x, acc_y, acc_z,
lin_x, lin_y, lin_z,
grav_x, grav_y, grav_z,
gyro_x, gyro_y, gyro_z,
rot_yaw, rot_pitch, rot_roll,
mag_heading
```

- `timestamp_ns`: `SensorEvent.timestamp` del evento maestro (acelerómetro). Reloj: `SystemClock.elapsedRealtimeNanos()`-like (monotónico, sin ajustes NTP).
- `timestamp_ms`: `SystemClock.elapsedRealtime()` en ms, útil para alinear con otras fuentes cuando no se necesita precisión sub-ms. Para wall-clock se usa `metadata.json` (`session_started_at_wall_ms` + offset).
- Separador: coma. Locale: `Locale.US` (punto decimal).
- Precisión: 4 decimales (`"%.4f"`). Suficiente para IMU en gama media-alta.
- Campos vacíos si el sensor no existe: `,,`.

### Tamaño estimado de una sesión de 8 h

~180 bytes/fila × 100 filas/s × 3600 s × 8 h ≈ **520 MB**. Reparto en chunks de 20 MB ⇒ ~26 archivos. Espacio libre requerido en `filesDir`: reservar ~700 MB.

### Rotación de chunks

Rotar cuando se cumpla **cualquiera** de los dos:

- `chunkDurationMs = 5 * 60_000` (5 min).
- `chunkMaxBytes = 20 * 1024 * 1024` (20 MiB).

Al rotar: `flush()` → `FileDescriptor.sync()` → cerrar writer → abrir `chunk_<NNN>.csv` con cabecera.

### Flush periódico

Cada 1 s (aprox. cada 100 frames) llamar a `BufferedWriter.flush()` para que, si el proceso muere, no se pierda más de ~1 s de datos.

### `metadata.json` por sesión

```json
{
  "session_id": "20260417_103045",
  "started_at_wall_ms": 1753171845000,
  "started_at_boot_ns": 123456789012345,
  "device": { "model": "Pixel 7", "manufacturer": "Google", "sdk": 34 },
  "app_version": "1.0",
  "sensors": [
    { "type": "TYPE_LINEAR_ACCELERATION", "name": "...", "resolution": 0.0023, "fifoMax": 300 }
  ],
  "columns": ["timestamp_ns", "timestamp_ms", "acc_x", ...],
  "chunk_duration_ms": 300000,
  "chunk_max_bytes": 20971520,
  "resume_of": null
}
```

Si el servicio se reinicia tras un kill del sistema, se genera una nueva sesión con `resume_of = <prev_session_id>` para mantener trazabilidad.

---

## 7. Almacenamiento

- **Ubicación**: almacenamiento interno de la app (`context.filesDir/sessions/<sid>/`). Privado, no requiere permisos de almacenamiento externo, no aparece en galería.
- **Export al final**: pantalla `SessionsScreen` lista las sesiones, permite:
  - **Compartir / Exportar** (SAF, el usuario elige destino). Implementación: concatenar chunks saltando cabeceras intermedias; o empaquetar todo en un `.zip` si la sesión tiene muchos chunks.
  - **Borrar** sesiones.
- **Limpieza automática**: al arrancar la app se purgan sesiones con flag `deleted=true` en `metadata.json` o sesiones incompletas de más de 30 días (política configurable).

---

## 8. Batería y pantalla bloqueada

Requisitos para grabar con la pantalla apagada en gama media-alta:

1. **Foreground Service** con `foregroundServiceType="dataSync"` (Android 14+ obliga a declarar tipo explícito).
2. **`PARTIAL_WAKE_LOCK`** mantenido durante toda la sesión (`ScanTest::RecordingWakeLock`, timeout 9 h). Libera en `onDestroy` y en `ACTION_STOP`.
3. **Exclusión de optimización de batería** solicitada al usuario vía `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Obligatoria; sin ella el sistema puede suspender el proceso tras unos minutos en doze.
4. **Batching hardware** (`maxReportLatencyUs = 200_000`): el AP puede dormir entre lotes, bajando consumo típico ~30–50 % frente a latencia 0.
5. **Eliminado el "truco de AudioTrack silencioso"** (obsoleto, frágil y fuera de política Play).

### Checklist por fabricante

A mostrar al usuario en primera ejecución según `Build.MANUFACTURER`:

| Fabricante            | Ajuste a guiar                                                                                                                      |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| **Xiaomi / Redmi / POCO** (MIUI) | "Autostart" (Seguridad → Permisos → Inicio automático). "Ahorro de batería → Sin restricciones". "Bloqueo de MIUI" para la app. |
| **Huawei / Honor**    | "Protected apps" / "App launch → Manage manually → habilitar auto-launch, secondary launch, run in background".                    |
| **OPPO / Realme / OnePlus** (ColorOS / OxygenOS) | "Battery → App battery usage → Allow background activity". "Startup manager → permitir autoarranque". |
| **Samsung** (One UI)  | "Device care → Battery → Background usage limits → Never sleeping apps → añadir la app".                                            |
| **Vivo / iQOO**       | "Background power consumption management → High background power consumption".                                                      |
| **Stock Android / Pixel / Motorola** | Sólo el diálogo estándar de `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.                                                        |

---

## 9. Política de errores y robustez

- **`START_STICKY`** en el servicio: si el sistema lo mata, al reiniciar vuelve a arrancar con el último intent. Si había sesión activa (detectada por presencia de un lock file `session.lock` en el dir activo), se crea una nueva sesión con `resume_of` y se sigue grabando.
- **Validación de disco**: antes de iniciar sesión, comprobar `filesDir.usableSpace > 1 GB`; si no, avisar al usuario y no arrancar.
- **Backpressure**: `Channel.DROP_OLDEST` con capacidad 2048 (~20 s de cola a 100 Hz). El `RecordingHealthTracker` cuenta drops; si > 0 es señal de que el I/O no llega — investigar disco lento.
- **Timeout del WakeLock**: siempre con timeout (no infinito) para evitar lock huérfano si el servicio muere de forma anómala.

---

## 10. Qué va y qué no va en cada fase

| Fase | Objetivo                                                                                                  |
| ---- | --------------------------------------------------------------------------------------------------------- |
| 1    | Este documento + reestructuración de paquetes (sin cambio funcional).                                    |
| 2    | Motor de captura correcto (SensorHub + FrameAssembler + RecordingEngine). Fin del freeze tras 30 min.    |
| 3    | Almacenamiento por chunks streaming, pantalla de sesiones, export streaming.                              |
| 4    | Servicio `dataSync`, onboarding por fabricante, `RecordingHealthTracker` en UI, validación 8 h.          |

**Fuera de alcance en esta iteración**:

- Compresión gzip de chunks (trivial añadir; decisión postergada).
- Sincronización con servidor.
- Room/SQLite (no aporta sobre CSV streaming en este dominio).
- Refactor a multi-módulo Gradle.
- Migración del namespace Android a `com.imuflux.app`.

---

## 11. Convenciones de código

- `snake_case` sólo en ficheros (CSV headers, JSON keys). Kotlin: `camelCase` para funciones/variables, `PascalCase` para clases y enums.
- Logs con tag por clase: `private val TAG = "SensorHub"`.
- No usar `Log.d` en hot path de sensores (en release). Sólo `Log.w`/`Log.e` para anomalías.
- Locale: siempre `Locale.US` para `String.format`/parseo numérico.
- Tiempos: siempre en nanosegundos para cálculos, sufijo `Ns` / `Ms` en nombres.

---

## 12. Cómo validar una sesión

Script externo Python (`tools/validate_session.py`, sólo stdlib):

```bash
python3 tools/validate_session.py /ruta/al/session_dir_o_csv_o_zip --strict
```

El script:

1. Acepta un directorio `sessions/<id>/`, un CSV exportado único o un ZIP exportado.
2. Lee todos los `chunk_*.csv` en orden saltando cabeceras intermedias duplicadas.
3. Verifica que las cabeceras son idénticas entre chunks.
4. Calcula `dt = diff(timestamp_ns)` y reporta: `dt_median`, `dt_mean`, `dt_p95`, `dt_p99`, `jitter_p95` (desviación respecto a 10 ms), número de huecos (`dt > 50 ms`), `max_gap`, duración total y nº de muestras.

**Criterios de aceptación** para 8 h con pantalla bloqueada:

* `dt_median ∈ [9.5 ms, 10.5 ms]`
* `gaps (dt > 50 ms) == 0`
* `jitter_p95 < 5 ms`

El procedimiento completo (preparación del dispositivo, configuración OEM, ejecución de la sesión y troubleshooting) está documentado en [`tools/LONG_SESSION_TEST.md`](tools/LONG_SESSION_TEST.md).
