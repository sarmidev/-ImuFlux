# Configuración operativa: control remoto de grabación

Guía paso a paso para dejar funcionando la pila completa:

- Autenticación admin en la app desktop.
- Lectura de diagnostics desde Firestore.
- Registro de token FCM desde Android.
- Envío de comandos Start/Stop desde el desktop vía Cloud Function.
- Recepción FCM en Android y arranque/parada de `RecordingService`.

---

## 1. Resumen del flujo

```
Android (dispositivo)
  └─ ScanTestApp.onCreate()
       ├─ FirebaseAuth.signInAnonymously()  →  deviceId = UID anónimo
       └─ FcmTokenRegistrar.registerCurrentToken()
            └─ escribe fcmToken en Firestore
                 diagnosticsDevices/{deviceId}

Desktop (operador admin)
  └─ Login con email/password Firebase
       └─ DiagnosticsDesktopViewModel.startRecording(deviceId)
            └─ RemoteCommandClient.startRecording(deviceId)
                 └─ POST /sendRemoteRecordingCommand
                       Authorization: Bearer <idToken admin>
                       { deviceId, command: "START_RECORDING", requestId }

Cloud Function (europe-west1)
  └─ Verifica idToken  →  requiere admin == true
  └─ Lee diagnosticsDevices/{deviceId}.fcmToken
  └─ messaging.send({ token, data: { command, requestId, issuedAt } })

Android (dispositivo)
  └─ RemoteRecordingMessagingService.onMessageReceived()
       └─ RemoteRecordingCommandHandler.handle(START_RECORDING)
            └─ ContextCompat.startForegroundService(RecordingService, ACTION_START)

Firestore (fuente de verdad)
  └─ ImuDiagnosticsAggregator escribe isRecording = true
       └─ Desktop refresca → muestra estado real
```

---

## 2. Requisitos previos

| Requisito | Detalle |
|---|---|
| Firebase CLI | `npm install -g firebase-tools` — verificar con `firebase --version` |
| Node ≥ 18 (recomendado 20) | Para compilar y desplegar `functions/` |
| npm | Viene con Node |
| Proyecto Firebase seleccionado | `firebase use imuflux` (project id del `.firebaserc`) |
| Plan Blaze (pago por uso) | Las Cloud Functions gen 2 requieren plan Blaze; el coste de uso ocasional es negligible |
| `app/google-services.json` correcto | Debe existir localmente (está en `.gitignore`); contiene la config del proyecto `imuflux` |
| Cuenta de usuario Firebase | Para que el operador se autentique en el desktop (Email/Password); **distinta** de las cuentas anónimas de los móviles |

> La cuenta anónima del móvil (`deviceId`) la genera automáticamente `FirebaseAuth.signInAnonymously()`. El operador que usa el desktop es un usuario **distinto**, con Email/Password y el custom claim `admin: true`.

---

## 3. Configurar Firebase Auth

### 3.1 Habilitar el proveedor Email/Password

1. Abre [Firebase Console → Authentication → Sign-in method](https://console.firebase.google.com/project/imuflux/authentication/providers).
2. Pulsa **Email/Password** → activa el primer toggle (_Enable_).
3. Guarda.

> El proveedor **Anonymous** también debe estar activo (para los móviles). Comprueba que lo está en la misma pantalla.

### 3.2 Crear el usuario operador

1. En [Firebase Console → Authentication → Users](https://console.firebase.google.com/project/imuflux/authentication/users), pulsa **Add user**.
2. Introduce el email y la contraseña del operador.
3. Copia el **UID** que aparece — lo necesitarás para verificar si algo va mal.

> Este usuario no tiene el claim `admin` todavía. Sin él, tanto las Firestore rules como la Cloud Function rechazarán sus llamadas.

---

## 4. Asignar el custom claim `admin`

Las Firestore rules y la Cloud Function exigen `request.auth.token.admin == true` / `decodedToken.admin === true`. Este claim **no se puede asignar desde la consola web**: requiere el Admin SDK.

### 4.1 Preparar el entorno (solo una vez)

Instala `firebase-admin` localmente (fuera del proyecto Gradle/npm de la app):

```bash
# En cualquier directorio temporal o directamente en la raíz del repo
npm install firebase-admin
```

O directamente con `npx` cuando ejecutes el script (no requiere instalación previa):

```bash
npx -y firebase-admin node scripts/set-admin-claim.js admin@example.com
```

### 4.2 Autenticar el Admin SDK

**Opción A — Application Default Credentials (recomendado)**

```bash
# Instala gcloud si no lo tienes: https://cloud.google.com/sdk/docs/install
gcloud auth application-default login
```

Selecciona tu cuenta de Google con acceso al proyecto `imuflux`.

**Opción B — Service Account descargado localmente**

1. En [Firebase Console → Project settings → Service accounts](https://console.firebase.google.com/project/imuflux/settings/serviceaccounts/adminsdk), pulsa **Generate new private key**.
2. Guarda el `.json` **solo en local**, por ejemplo en `scripts/service-account-imuflux.json`.
3. Ese archivo está excluido por `.gitignore` (`*service-account*.json`). **No lo commites.**

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/ruta/absoluta/scripts/service-account-imuflux.json"
```

### 4.3 Ejecutar el script

```bash
node scripts/set-admin-claim.js admin@example.com
```

Salida esperada:

```
✅  Claim admin:true asignado a admin@example.com (uid: xxxxxxxxxxxxxxx)
   El usuario debe cerrar sesión y volver a iniciarla para que el ID token refleje el claim actualizado.
```

### 4.4 Refrescar el token del usuario

El claim queda grabado en Firebase, pero el ID token actual del usuario (si ya estaba logueado) **no lo incluye todavía**. El usuario debe:

- En el desktop: **Salir** (botón "Salir") y volver a hacer **Login**.
- El nuevo ID token llevará `admin: true` en sus claims.

### 4.5 Revocar el claim (si hace falta)

```bash
# Modifica set-admin-claim.js para pasar {} en lugar de { admin: true }
# o ejecuta directamente:
node -e "
const admin = require('firebase-admin');
admin.initializeApp();
admin.auth().getUserByEmail('admin@example.com')
  .then(u => admin.auth().setCustomUserClaims(u.uid, {}))
  .then(() => { console.log('claim eliminado'); process.exit(0); });
"
```

---

## 5. Desplegar Firestore rules

Las reglas ya están en `firestore.rules` con la lógica correcta:
- `diagnosticsDevices`: read solo admin, write solo al owner del documento.
- Subcollections `sessions` y `healthWindows`: igual.
- Campos FCM (`fcmToken`, `fcmTokenUpdatedAt`, `fcmTokenInvalidAt`) validados como tipos correctos cuando presentes.

### 5.1 Seleccionar proyecto y desplegar

```bash
firebase use imuflux
firebase deploy --only firestore:rules
```

### 5.2 Verificar en la consola

1. Abre [Firebase Console → Firestore → Rules](https://console.firebase.google.com/project/imuflux/firestore/rules).
2. Usa el simulador integrado para comprobar:
   - **Read** `diagnosticsDevices/test` como usuario autenticado sin claim → debe ser **denegado**.
   - **Read** `diagnosticsDevices/test` como usuario con `admin: true` → debe ser **permitido**.
   - **Write** `diagnosticsDevices/test` (con campos válidos) como usuario anónimo cuyo UID == `test` → debe ser **permitido**.

---

## 6. Desplegar Cloud Functions

```bash
cd functions
npm install
npm run build    # compila TypeScript → lib/
npm run lint     # verifica estilo
npm test         # 8 tests unitarios de validación
cd ..
firebase deploy --only functions
```

Salida esperada al final del deploy:

```
✔  functions[sendRemoteRecordingCommand(europe-west1)]: Successful create operation.
Function URL (sendRemoteRecordingCommand(europe-west1)):
  https://europe-west1-imuflux.cloudfunctions.net/sendRemoteRecordingCommand
```

### 6.1 Ver logs en tiempo real

```bash
firebase functions:log --only sendRemoteRecordingCommand
```

O en la consola: [Firebase Console → Functions → Logs](https://console.firebase.google.com/project/imuflux/functions/logs).

### 6.2 Probar el endpoint manualmente (opcional)

```bash
# Obtén primero un ID token del usuario admin (válido ~1 h):
# Puedes extraerlo de la app desktop mientras está logueado con las DevTools,
# o con la REST API de Identity Toolkit:
ID_TOKEN="<pega-el-id-token-aqui>"

curl -s -X POST \
  -H "Authorization: Bearer $ID_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"<uid-del-dispositivo>","command":"START_RECORDING","requestId":"test-1"}' \
  https://europe-west1-imuflux.cloudfunctions.net/sendRemoteRecordingCommand | jq .
```

Respuesta de éxito:

```json
{"success": true, "deviceId": "...", "command": "START_RECORDING", "messageId": "..."}
```

---

## 7. Configurar el desktop

### 7.1 Obtener la Web API key

1. Abre [Firebase Console → Project settings → General](https://console.firebase.google.com/project/imuflux/settings/general).
2. En la sección **Your apps**, busca la app web o copia la **Web API key** que aparece en la parte superior de Project settings.
3. También está en `app/google-services.json` como `current_key` (solo para desarrollo local).

### 7.2 Opción A — Variables de entorno (recomendado para CI o terminal)

Añade al perfil de tu shell (`~/.zshrc`, `~/.bash_profile`, etc.) o al entorno del proceso:

```bash
export IMUFLUX_FIREBASE_API_KEY="AIzaS...tu-clave-aqui"
export IMUFLUX_FIREBASE_PROJECT_ID="imuflux"

# Opcional: URL explícita de la Function (si quieres usar emulador o staging)
export IMUFLUX_REMOTE_COMMANDS_URL="https://europe-west1-imuflux.cloudfunctions.net/sendRemoteRecordingCommand"
```

Recarga el entorno (`source ~/.zshrc`) y luego lanza el desktop.

### 7.2 Opción B — Archivo local (más cómodo para desarrollo)

Crea el archivo `desktopApp/local.properties` (está en `.gitignore`, no se comitea):

```properties
firebase.apiKey=AIzaS...tu-clave-aqui
firebase.projectId=imuflux

# Opcional: solo si quieres sobreescribir la URL por defecto de la Function
# remoteCommands.url=https://europe-west1-imuflux.cloudfunctions.net/sendRemoteRecordingCommand
```

### 7.3 Lanzar la app desktop

```bash
./gradlew :desktopApp:run
```

O genera un distributable:

```bash
./gradlew :desktopApp:packageDmg   # macOS
./gradlew :desktopApp:packageMsi   # Windows
./gradlew :desktopApp:packageDeb   # Linux
```

> Si el desktop no encuentra la config, mostrará la pantalla `ConfigErrorScreen` con instrucciones. Si encuentra la config pero el usuario no tiene `admin: true`, el login tendrá éxito pero las llamadas a Firestore y a la Function serán rechazadas con error de permisos.

---

## 8. Preparar Android

### 8.1 Instalar la app

Compila e instala la build debug en el dispositivo:

```bash
./gradlew :app:installDebug
```

O compila la release y despliégala por tu canal habitual.

### 8.2 Primer arranque

Abre la app al menos una vez con conexión a internet. En segundo plano sucede automáticamente:

1. `FirebaseAuth.signInAnonymously()` → resuelve el **deviceId** (UID anónimo persistente).
2. `FcmTokenRegistrar.registerCurrentToken()` → obtiene el token FCM y lo escribe en `diagnosticsDevices/{deviceId}.fcmToken`.

No hay ningún botón que pulsar: el registro es automático en `ScanTestApp.onCreate()`.

### 8.3 Verificar en Firestore

1. Abre [Firebase Console → Firestore → Data](https://console.firebase.google.com/project/imuflux/firestore/data).
2. Navega a la colección `diagnosticsDevices`.
3. Busca el documento cuyo id coincide con el UID anónimo del dispositivo.
4. Confirma que el campo `fcmToken` existe y no está vacío.
5. El campo `fcmTokenUpdatedAt` debe ser un timestamp reciente.

> **Cómo saber el `deviceId`**: en Android Studio, abre Logcat y filtra por el tag `FcmTokenRegistrar`. Verás el mensaje  
> `FCM token updated on existing device doc (xxxxxxxx)` o  
> `FCM token written via minimal device doc (xxxxxxxx)`.  
> Ese `xxxxxxxx` es el `deviceId`.

### 8.4 Permisos de notificación (Android 13+)

Si el dispositivo usa Android 13 o superior y la app no tiene permiso `POST_NOTIFICATIONS`, los mensajes FCM de alta prioridad pueden no despertar la app. En ese caso:

1. Ve a **Ajustes → Apps → ImuFlux → Notificaciones**.
2. Activa las notificaciones para la app.

### 8.5 Excención de optimización de batería

Para que el foreground service arranque de forma fiable desde un mensaje FCM de alta prioridad, la app debe estar exenta de la optimización de batería:

1. Ve a **Ajustes → Batería → Optimización de batería → ImuFlux**.
2. Selecciona **No optimizar**.

El propio flujo local ya guarda esta exención; el control remoto la necesita también.

---

## 9. Prueba manual end-to-end

### Paso 1 — Abrir el desktop y hacer login

1. Lanza la app desktop.
2. Introduce el email y la contraseña del usuario admin.
3. Pulsa **Iniciar sesión**.

Si aparece "Permiso denegado / admin == true", el claim no está asignado aún. Vuelve al paso 4.

### Paso 2 — Ver los dispositivos

1. La pantalla principal muestra la lista de dispositivos.
2. Busca el dispositivo que quieres controlar.
3. Confirma que tiene el badge verde **"Control remoto"** — indica que `fcmToken` está presente y es válido.

> Si el badge no aparece, el dispositivo aún no ha registrado su token FCM. Abre la app Android y espera unos segundos.

### Paso 3 — Enviar START

1. Pulsa el dispositivo para abrir su pantalla de detalle.
2. En la tarjeta **Control remoto**:
   - Confirma que el badge "Sin grabar" es el estado actual.
   - Pulsa **Iniciar grabación**.
3. Aparece un spinner mientras el comando está en vuelo.
4. Tras el envío verás el mensaje: `Comando START_RECORDING enviado. Confirmando estado…`
5. El desktop espera ~4 s y luego refresca automáticamente el dispositivo.
6. Si todo fue bien, el badge cambia a **Grabando** y el botón **Iniciar grabación** se deshabilita.

### Paso 4 — Confirmar en el dispositivo Android

En el dispositivo deberías ver:
- La notificación persistente de grabación activa.
- En Logcat (tag `RemoteRecCmdHandler`): `START dispatched to RecordingService`.

### Paso 5 — Enviar STOP

1. En el desktop, con el dispositivo mostrando **Grabando**, pulsa **Parar grabación**.
2. Repite el proceso: spinner → mensaje de confirmación → refresco automático.
3. El badge vuelve a **Sin grabar**.

### Qué mirar en logs si algo falla

```bash
# Logs de la Cloud Function en tiempo real:
firebase functions:log --only sendRemoteRecordingCommand

# Logcat Android (en Android Studio o adb):
adb logcat -s FcmTokenRegistrar RemoteRecCmdHandler RemoteRecMsgService RecordingService
```

---

## 10. Troubleshooting

### Login funciona pero el dashboard está vacío o da "permiso denegado"

- **Causa**: el usuario admin no tiene el custom claim `admin: true` en su ID token.
- **Solución**: ejecuta `node scripts/set-admin-claim.js <email>`, luego haz logout/login en el desktop.
- Verifica: después del login, la llamada a Firestore devuelve 403 con `PERMISSION_DENIED` → confirma el claim con `firebase auth:export` o la consola.

### La Cloud Function devuelve 401

- **Causa**: la cabecera `Authorization: Bearer ...` es inválida o el token ha expirado.
- **Solución**: haz logout/login en el desktop para obtener un token fresco. El `SessionManager` debería renovarlo automáticamente; si no, comprueba los logs del desktop.

### La Cloud Function devuelve 403

- **Causa**: el usuario está autenticado pero `admin !== true` en el token.
- **Solución**: asigna el claim (paso 4) y vuelve a hacer login.

### La Cloud Function devuelve 409 — sin token FCM

- **Causa**: el documento `diagnosticsDevices/{deviceId}` existe pero no tiene el campo `fcmToken`, o está vacío.
- **Solución**:
  1. Abre la app Android (tiene que estar instalada con la versión que incluye `FcmTokenRegistrar`).
  2. Espera 5-10 segundos y refresca el dispositivo en el desktop.
  3. Verifica en Firestore que `fcmToken` aparece.
  4. Si no aparece, revisa el Logcat con el tag `FcmTokenRegistrar`.

### La Cloud Function devuelve 410 — token FCM inválido, se ha limpiado

- **Causa**: el token FCM guardado ya no está registrado en FCM (el usuario desinstalé la app, borró datos o un token muy antiguo fue invalidado por FCM).
- **Solución**: reinstala/reabre la app Android. `FcmTokenRegistrar` obtendrá un token nuevo y lo escribirá. El campo `fcmTokenInvalidAt` se limpiará también en ese momento.

### Android no arranca la grabación (ForegroundServiceStartNotAllowedException)

- **Causa**: Android 12+ restringe el arranque de foreground services desde el background. Si la app lleva mucho tiempo en Doze/cache, el sistema puede rechazar el intento aunque el mensaje FCM tenga `priority: high`.
- **Mitigaciones**:
  1. Asegura que la app está exenta de optimización de batería (paso 8.5).
  2. Deja la app en primer plano o en la bandeja de notificaciones antes de enviar el comando.
  3. Revisa el Logcat: tag `RemoteRecCmdHandler`, mensaje `START failed — could not launch foreground service`.
- **Nota**: STOP no tiene este problema porque solo manda un intent a un servicio ya en marcha.

### `isRecording` tarda en actualizar o no cambia

- **Causa más común**: el comando FCM llegó al dispositivo, pero `ImuDiagnosticsAggregator` tarda hasta ~10 segundos en hacer el primer flush de `isRecording = true` a Firestore tras el inicio.
- **Solución**: pulsa **Refrescar** en el panel de detalle después de unos 15 segundos. El estado final de verdad es siempre el de Firestore, no la respuesta de la Cloud Function.
- Si tras 30 s sigue en `false`, revisa el Logcat del dispositivo.

### El dispositivo desaparece o cambia de `deviceId` tras borrar datos de la app

- **Causa**: `deviceId` es el UID anónimo de Firebase Auth. Borrar datos de la app elimina la sesión anónima local; en el próximo inicio Firebase crea un **nuevo** UID.
- **Consecuencia**: el documento antiguo en Firestore queda huérfano (con el token FCM inválido) y el nuevo dispositivo aparece con un `deviceId` distinto.
- **No es un bug**: es el comportamiento esperado de Firebase Anonymous Auth. Documenta el `deviceId` si necesitas rastrearlo.

### Desktop no encuentra la configuración Firebase

- **Síntoma**: la pantalla muestra `Firebase configuration missing` con instrucciones.
- **Verificación**:
  - ¿Existen las variables de entorno `IMUFLUX_FIREBASE_API_KEY` e `IMUFLUX_FIREBASE_PROJECT_ID`?
  - ¿Existe `desktopApp/local.properties` con `firebase.apiKey` y `firebase.projectId`?
  - Si ninguna de las dos, el fallback lee `app/google-services.json` (solo en desarrollo, mismo equipo que el repo). Comprueba que ese archivo existe localmente.

### URL de la Cloud Function incorrecta

- **Síntoma**: `RemoteCommandException: Error de red` o respuesta 404/502 inesperada.
- **Verificación**:
  1. Comprueba la URL en la salida del deploy o en [Firebase Console → Functions](https://console.firebase.google.com/project/imuflux/functions).
  2. Establece `IMUFLUX_REMOTE_COMMANDS_URL` explícitamente o añade `remoteCommands.url` en `desktopApp/local.properties`.
  3. La URL por defecto generada en código es `https://europe-west1-imuflux.cloudfunctions.net/sendRemoteRecordingCommand`.

---

## 11. Checklist final

Antes de operar en producción, verifica cada punto:

- [ ] Firebase Auth: proveedor **Email/Password** habilitado.
- [ ] Firebase Auth: proveedor **Anonymous** habilitado.
- [ ] Usuario operador creado en Firebase Auth.
- [ ] Custom claim `admin: true` asignado al usuario operador.
- [ ] Usuario operador ha hecho logout/login después de asignar el claim.
- [ ] Firestore rules desplegadas: `firebase deploy --only firestore:rules`.
- [ ] Cloud Function desplegada: `firebase deploy --only functions`.
- [ ] Endpoint de la Function accesible (curl de prueba o consola).
- [ ] Desktop configurado (env vars o `desktopApp/local.properties`).
- [ ] App Android instalada con la versión que incluye `FcmTokenRegistrar` y `RemoteRecordingMessagingService`.
- [ ] App Android abierta al menos una vez con conexión → `fcmToken` visible en Firestore.
- [ ] Prueba end-to-end completada: Start → `isRecording == true` → Stop → `isRecording == false`.

---

## Apéndice — Archivos relevantes del repo

| Archivo / Carpeta | Propósito |
|---|---|
| `firestore.rules` | Reglas de seguridad Firestore |
| `firebase.json` | Config Firebase (Firestore + Functions) |
| `.firebaserc` | Proyecto por defecto: `imuflux` |
| `functions/` | Cloud Function TypeScript |
| `functions/src/index.ts` | `sendRemoteRecordingCommand` |
| `functions/src/validation.ts` | Validación pura del payload (testeable) |
| `scripts/set-admin-claim.js` | Script para asignar `admin: true` |
| `desktopApp/local.properties` | Config local desktop (git-ignored) |
| `shared/…/RemoteRecordingCommand.kt` | Constantes compartidas (wire values) |
| `app/…/FcmTokenRegistrar.kt` | Registro automático del token FCM |
| `app/…/RemoteRecordingMessagingService.kt` | Receptor FCM en Android |
| `app/…/RemoteRecordingCommandHandler.kt` | Ejecutor idempotente de comandos |
| `desktopApp/…/RemoteCommandClient.kt` | Cliente HTTP de la Cloud Function |
| `desktopApp/…/DeviceDetailScreen.kt` | UI de control remoto (Start/Stop) |
