# Procedimiento de test de sesión larga (8 h con pantalla bloqueada)

Este documento describe cómo validar de extremo a extremo que la app cumple
el objetivo de grabar IMU a **100 Hz constantes durante 8 h con la pantalla
apagada**.

El script asociado es `tools/validate_session.py`.

---

## 1. Preparación del dispositivo

Usar un dispositivo de gama media-alta (p.ej. Pixel 7, Samsung S22, Xiaomi 13).

1. Cargar batería al **100 %** antes de empezar.
2. Activar modo avión si se puede (elimina interrupciones de red y ahorra
   batería). Si se necesita Bluetooth/GPS para otra prueba, documentarlo.
3. Cerrar el resto de apps.
4. Instalar la build **release** (no debug — el debug añade jitter por el
   debugger y by-pass de algunas optimizaciones).

## 2. Configuración inicial en la app

1. Abrir ImuFlux.
2. Aceptar el diálogo **"Configuración necesaria"** y desactivar la
   optimización de batería para la app.
3. Aceptar el diálogo de **fabricante** (guía OEM) y aplicar los ajustes
   recomendados (Autostart, apps sin suspensión, etc.).
4. Conceder permiso de notificación si Android 13+ lo solicita.
5. Verificar en **Ajustes → Apps → ImuFlux → Batería** que aparece como
   "Sin restricciones".

## 3. Arranque de la grabación

1. Asegurarse de que el panel de salud en la pantalla principal muestra
   valores entrando (samples/s ≈ 100).
2. Pulsar **"Empezar a Grabar"**.
3. Confirmar que:
   * Aparece la notificación persistente "Grabación IMU activa".
   * El panel de salud muestra `samples/s ≈ 100` con `jitter p95 < 5 ms`.
   * El contador `chunk #` empieza en 0 y los bytes suben.
4. Bloquear la pantalla (botón lateral).
5. Colocar el dispositivo sobre una superficie plana (el contenido de la
   señal es secundario; lo que validamos es el pipeline, no el contenido).
6. **No tocar** el dispositivo durante 8 horas.

## 4. Durante la sesión

Opcionalmente, cada 2 h encender la pantalla (sin desbloquear) para verificar
que la notificación persistente sigue visible. Si desaparece, algo ha matado
el proceso: documentar marca, modelo y versión de Android.

## 5. Fin de la sesión

1. Desbloquear la pantalla.
2. Pulsar **"Parar Grabación"** (o detener desde la notificación).
3. Abrir el icono de lista en la barra superior → **Sesiones grabadas**.
4. Verificar que la última sesión:
   * Tiene duración ≈ 8 h.
   * Tiene `chunk_count` ≈ 96 (8 h × 60 min / 5 min por chunk).
   * El tamaño total está en torno a los 500 MB.
5. Pulsar **"Exportar ZIP"** y guardar en almacenamiento externo / SAF.

## 6. Validación con Python

```bash
python3 tools/validate_session.py /ruta/al/imuflux_20260417_083000.zip --strict
```

El script imprime:

```
rows              =      2880000
duration_s        =      28800.00
dt_mean_ms        =      10.0000
dt_median_ms      =      10.0000
dt_p95_ms         =      10.2500
dt_p99_ms         =      10.8000
jitter_p95_ms     =       0.2500
gaps (>50 ms)     =            0
max_gap_ms        =       0.9200
OK: la sesión cumple los criterios de 100 Hz sostenidos
```

### Criterios de aceptación

| Métrica       | Umbral                      |
| ------------- | --------------------------- |
| `dt_median`   | 9.5 – 10.5 ms               |
| `gaps`        | 0 (ningún `dt > 50 ms`)     |
| `jitter_p95`  | < 5 ms                      |

Si **cualquiera** falla, el test no pasa. Capturar logcat filtrado por
`SensorHub|RecordingEngine|CsvChunkWriter|RecordingService` y adjuntar al
informe.

## 7. Qué hacer si falla

| Síntoma                                       | Causa probable                               | Acción                                                                 |
| --------------------------------------------- | -------------------------------------------- | ---------------------------------------------------------------------- |
| `duration_s` muy corta (< 8 h)                | Proceso killed por OEM / doze agresivo       | Revisar ajustes de fabricante; añadir instrucciones al onboarding      |
| `gaps > 0` en momentos aislados               | GC o suspend del driver                      | Reducir `maxReportLatencyUs` a 100_000 y repetir                       |
| `dt_median` fuera de rango (95 Hz)            | Dispositivo limita sensor a < 100 Hz         | `Sensor.minDelay` en `metadata.json` — no es fallo de la app           |
| `jitter_p95 > 5 ms` pero sin gaps             | HandlerThread compitiendo por CPU            | Verificar que no hay otras apps activas; revisar `THREAD_PRIORITY`     |
| `framesDropped > 0` en UI                     | I/O lento (disco saturado / flash gastado)   | Liberar espacio; sustituir dispositivo                                  |
