# Política de Privacidad — ImuFlux

**Versión:** 1.0  
**Fecha de vigencia:** 14 de mayo de 2026  
**Desarrollador:** Sarmi Dev  
**Identificador de la aplicación:** `com.sarmidev.imuflux`  
**Contacto:** [insertar correo de contacto]

---

## 1. Introducción

ImuFlux es una aplicación Android diseñada para el registro continuo de datos de movimiento mediante sensores de unidad de medición inercial (IMU) a alta frecuencia (100 Hz), orientada a entornos industriales y logísticos (operación de montacargas, almacenes, etc.). Esta política explica qué datos recopila la aplicación, cómo los usa, dónde los almacena y con quién los comparte.

Al instalar y usar ImuFlux aceptas las condiciones descritas en este documento.

---

## 2. Datos que recopila la aplicación

### 2.1 Datos de sensores del dispositivo

Durante una sesión de grabación, la aplicación registra las siguientes señales del hardware del dispositivo a ~100 lecturas por segundo:

| Sensor | Datos capturados |
|---|---|
| Aceleración lineal | Vector X, Y, Z (m/s²) |
| Gravedad | Vector X, Y, Z (m/s²) |
| Giroscopio | Velocidad angular X, Y, Z (rad/s) |
| Vector de rotación | Ángulos yaw, pitch, roll derivados |

> El acelerómetro bruto y el magnetómetro/campo magnético están **desactivados** en la versión actual y no se registran.

### 2.2 Metadatos de sesión

Junto con los datos de sensores, se almacena información de contexto por cada sesión:

- **Identificador único de sesión** (UUID generado localmente)
- **Marca de tiempo** de inicio y fin (hora de pared y tiempo de arranque del sistema)
- **Modelo de montacargas** y **almacén** — ingresados manualmente por el usuario
- **Versión de la aplicación**
- **Información del dispositivo:** modelo (`Build.MODEL`), fabricante (`Build.MANUFACTURER`), versión de Android SDK (`Build.VERSION.SDK_INT`)
- **Descriptores de sensores:** nombre, fabricante del sensor, resolución, retardo mínimo, capacidad FIFO
- **Número de reinicios del watchdog** durante la sesión

### 2.3 Datos ingresados por el usuario

- **Nombre del modelo de montacargas** y **nombre del almacén**, usados como contexto de sesión.
- **Movimientos personalizados** (nombre, criterios de detección, estado activo/inactivo) — definidos por el usuario para identificar eventos de movimiento de interés. Estos datos se mantienen solo en memoria durante la ejecución de la app y **no se persisten en disco ni se transmiten a servidores**.

---

## 3. Almacenamiento local de datos

Los datos de sesión se guardan en el **almacenamiento privado interno** de la app (`filesDir/sessions/<session_id>/`), inaccesible para otras aplicaciones sin privilegios de root:

```
sessions/
  <session_id>/
    metadata.json      ← Metadatos de la sesión
    chunk_000.csv      ← Fragmentos de datos de sensores
    chunk_001.csv
    ...
```

Los archivos permanecen en el dispositivo hasta que el usuario los elimina manualmente desde la propia aplicación.

---

## 4. Exportación de datos

El usuario puede exportar una sesión en cualquier momento en dos formatos:

- **CSV único** — todos los fragmentos concatenados en un solo archivo.
- **ZIP** — carpeta completa de la sesión (metadatos + todos los fragmentos).

El destino del archivo exportado lo elige el usuario mediante el selector de archivos del sistema (Storage Access Framework). La app **no tiene acceso al almacenamiento general del dispositivo** sin la interacción explícita del usuario. Los archivos exportados quedan bajo la responsabilidad del usuario una vez guardados fuera de la app.

---

## 5. Datos transmitidos a servidores remotos

### 5.1 Qué se envía

La aplicación **no transmite datos de sensores en bruto** a ningún servidor. Únicamente se envía un **resumen de análisis de sesión** a Firebase Firestore, que incluye:

- Identificador de sesión
- Clave de dispositivo (derivada del modelo + fabricante + SDK)
- Información del dispositivo (fabricante, modelo, versión Android)
- Contexto de sesión (modelo de montacargas, almacén, versión de la app)
- Marcas de tiempo de inicio y fin
- Resultado del análisis de calidad de la sesión:
  - Estadísticas de temporización e intervalos de muestreo
  - Veredicto de compatibilidad (`EXCELLENT`, `GOOD`, `FAIR`, `POOR`, `UNUSABLE`)
  - Brechas detectadas en la señal
  - Puntuación de completitud de sensores
  - Conteo de resurrecciones del watchdog

### 5.2 Cuándo se envía

La transmisión ocurre **únicamente cuando el usuario activa el análisis de una sesión** desde la pantalla de sesiones. No hay transmisión automática ni en segundo plano sin acción del usuario.

### 5.3 Colecciones de Firestore

| Colección | Contenido |
|---|---|
| `sessions` | Un documento por sesión analizada con todos los campos descritos arriba |
| `deviceModels` | Agregado estadístico por modelo de dispositivo (puntuación de compatibilidad acumulada) |

Los datos en `deviceModels` se usan para construir el **ranking público de compatibilidad de dispositivos** visible en la pantalla de clasificación de la app.

---

## 6. Autenticación

La aplicación utiliza **Firebase Anonymous Authentication**. No existe registro, inicio de sesión ni cuenta de usuario. Antes de realizar escrituras en Firestore, se crea automáticamente una sesión anónima en Firebase. Este identificador anónimo **no está vinculado a ningún dato personal identificable** y no se expone al usuario.

---

## 7. Servicios de terceros

| Servicio | Proveedor | Propósito |
|---|---|---|
| Firebase Firestore | Google LLC | Almacenamiento remoto de análisis de sesiones y ranking de dispositivos |
| Firebase Authentication | Google LLC | Autenticación anónima para autorización de escritura en Firestore |

Los datos enviados a Firebase son procesados en la infraestructura de Google conforme a la [Política de Privacidad de Google](https://policies.google.com/privacy) y a los [Términos de Servicio de Firebase](https://firebase.google.com/terms). No se utilizan otros servicios de análisis, seguimiento de fallos (crashlytics) ni publicidad.

---

## 8. Permisos del dispositivo

| Permiso | Justificación |
|---|---|
| `INTERNET` | Transmisión de análisis a Firebase Firestore |
| `FOREGROUND_SERVICE` | Mantener el servicio de grabación activo durante sesiones largas |
| `FOREGROUND_SERVICE_DATA_SYNC` | Tipo de servicio en primer plano (Android 10–13) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Registro IMU continuo a 100 Hz durante turnos completos (Android 14+) |
| `WAKE_LOCK` | Prevenir que el procesador entre en reposo durante la grabación |
| `POST_NOTIFICATIONS` | Mostrar notificación persistente durante grabación (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reducir el impacto de Doze Mode en sesiones largas |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Alarmas periódicas del watchdog para supervisar el servicio |
| `RECEIVE_BOOT_COMPLETED` | Rearmar el watchdog tras reinicio del dispositivo |

La aplicación **no solicita** permisos de ubicación (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), acceso a cámara, micrófono, agenda de contactos ni almacenamiento general.

---

## 9. Datos de menores

ImuFlux está diseñada para uso profesional en entornos industriales. No está dirigida a personas menores de 16 años y no recopila deliberadamente información de menores.

---

## 10. Retención de datos

| Tipo de dato | Ubicación | Retención |
|---|---|---|
| Archivos CSV de sesión | Dispositivo (privado) | Hasta que el usuario los elimine |
| Metadatos de sesión | Dispositivo (privado) | Hasta que el usuario elimine la sesión |
| Resumen de análisis | Firebase Firestore (`sessions`) | Indefinida (bajo configuración del proyecto Firebase) |
| Agregado de dispositivo | Firebase Firestore (`deviceModels`) | Indefinida |
| Sesión anónima Firebase | Firebase Auth | Según política de retención de Firebase |

---

## 11. Derechos del usuario

En la medida aplicable según las leyes de protección de datos vigentes (RGPD, LOPDGDD u otras), el usuario tiene derecho a:

- **Acceso:** Solicitar qué datos vinculados a su dispositivo o sesiones se han almacenado.
- **Rectificación:** Corregir datos incorrectos.
- **Supresión:** Solicitar la eliminación de los datos de sesión almacenados en Firestore.
- **Portabilidad:** Exportar los datos de sesión en formato CSV o ZIP desde la propia app.
- **Oposición:** Interrumpir la transmisión de análisis simplemente no ejecutando el análisis de sesiones.

Para ejercer cualquiera de estos derechos, contacta a: **[insertar correo de contacto]**

---

## 12. Seguridad

- Los datos de sensores se almacenan en el **almacenamiento privado de la app**, protegido por el sandbox de Android.
- Las comunicaciones con Firebase utilizan **TLS/HTTPS**.
- Las reglas de seguridad de Firestore restringen la escritura a usuarios autenticados (anónimos) y la lectura de rankings al público general.
- La aplicación no almacena credenciales de usuario ni tokens en texto plano.

---

## 13. Cambios en esta política

Cuando se realicen cambios materiales en esta política, se actualizará la fecha de vigencia en la parte superior del documento. Se recomienda revisar esta política periódicamente. El uso continuado de la aplicación tras la publicación de cambios implica la aceptación de los mismos.

---

## 14. Contacto

Si tienes preguntas, comentarios o solicitudes relacionadas con esta política de privacidad, puedes contactarnos en:

**Sarmi Dev**  
Correo electrónico: [insertar correo de contacto]  
Sitio web: [insertar URL si aplica]

---

*Este documento fue elaborado a partir del análisis del código fuente de ImuFlux v1.x y refleja el comportamiento real implementado de la aplicación.*
