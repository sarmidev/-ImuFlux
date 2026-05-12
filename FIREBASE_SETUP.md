# Firebase Setup para ImuFlux

Este documento describe lo que hay que preparar en Firebase Console para que la app Android pueda subir cada analisis de sesion a Cloud Firestore y mantener un agregado por modelo de telefono.

## 1. Crear o elegir proyecto Firebase

1. Entra en https://console.firebase.google.com/.
2. Crea un proyecto nuevo o abre uno existente.
3. Si creas uno nuevo, puedes desactivar Google Analytics si no lo necesitas ahora.

## 2. Registrar la app Android

1. En el proyecto Firebase, pulsa el icono de Android para anadir una app.
2. Usa este package name:

```text
com.example.scantest
```

3. El nickname puede ser `ImuFlux Android`.
4. SHA-1 no es necesario para Firestore + Auth anonimo en esta fase.
5. Descarga `google-services.json`.
6. Coloca el archivo aqui:

```text
app/google-services.json
```

El archivo esta en `.gitignore` porque contiene identificadores del proyecto Firebase. La build aplicara el plugin de Google Services automaticamente cuando el archivo exista.

## 3. Activar Authentication anonimo

1. Ve a `Build > Authentication`.
2. Pulsa `Get started`.
3. Abre `Sign-in method`.
4. Activa `Anonymous`.
5. Guarda.

La app usa Auth anonimo para evitar escrituras publicas sin autenticar. Cada instalacion tendra un usuario anonimo de Firebase.

## 4. Crear Firestore

1. Ve a `Build > Firestore Database`.
2. Pulsa `Create database`.
3. Elige `Production mode`.
4. Elige una region europea. Recomendado para uso en Espana/UE:

```text
eur3
```

Si `eur3` no aparece, elige una region europea disponible. La region no se puede cambiar despues sin migracion.

## 5. Colecciones que creara la app

No hace falta crearlas manualmente: Firestore crea colecciones al escribir el primer documento. La app escribira:

```text
sessions/{sessionId}
deviceModels/{deviceKey}
```

### sessions/{sessionId}

Un documento por sesion analizada. Guarda el historico completo.

```json
{
  "sessionId": "20260511_181500",
  "deviceKey": "samsung__galaxy_a52__sdk_34",
  "device": {
    "manufacturer": "Samsung",
    "model": "Galaxy A52",
    "sdkInt": 34
  },
  "context": {
    "forkliftModel": "Toyota",
    "warehouse": "Madrid-01",
    "appVersion": "1.0"
  },
  "timestamps": {
    "startedAtWallMs": 1778512345678,
    "endedAtWallMs": 1778522345678,
    "analyzedAt": "serverTimestamp"
  },
  "analysis": {
    "schemaVersion": 1,
    "totalRows": 1234567,
    "durationS": 14400.0,
    "dtMedianMs": 10.0,
    "dtMeanMs": 10.02,
    "jitterP95Ms": 3.7,
    "gaps": 0,
    "maxGapMs": 0.0,
    "completenessPercent": 99.4,
    "watchdogResurrections": 0,
    "timingPassed": true,
    "timingErrors": [],
    "dataProblems": 0,
    "verdict": "PASS",
    "rawVerdict": "PASS"
  },
  "sensorGroups": [
    { "name": "Acelerometro (raw)", "status": "OK", "detail": "Completo" }
  ]
}
```

Si se analiza de nuevo la misma `sessionId`, la app actualiza este documento, pero no vuelve a contarla en `deviceModels`.

### deviceModels/{deviceKey}

Un documento agregado por modelo + version SDK. Sirve para rankings y futuras pantallas.

```json
{
  "manufacturer": "Samsung",
  "model": "Galaxy A52",
  "sdkInt": 34,
  "deviceKey": "samsung__galaxy_a52__sdk_34",
  "firstSeenAt": "serverTimestamp",
  "lastSeenAt": "serverTimestamp",
  "stats": {
    "sessionCount": 8,
    "passCount": 5,
    "warnCount": 2,
    "failCount": 1,
    "insufficientDataCount": 0,
    "avgCompleteness": 0.972,
    "avgJitterP95Ms": 4.1,
    "avgMedianDtMs": 10.03,
    "avgDurationS": 12600.0,
    "totalGaps": 6,
    "totalWatchdogResurrections": 1
  },
  "compatibility": {
    "score": 82,
    "category": "GOOD",
    "lastVerdict": "PASS",
    "lastRawVerdict": "PASS"
  }
}
```

La media incremental se calcula asi:

```text
newAverage = ((oldAverage * oldCount) + newValue) / (oldCount + 1)
```

## 6. Reglas de seguridad iniciales

Ve a `Firestore Database > Rules` y pega una version inicial como esta:

```js
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() {
      return request.auth != null;
    }

    function isValidVerdict(value) {
      return value in ['PASS', 'WARN', 'FAIL', 'INSUFFICIENT_DATA'];
    }

    function isValidCategory(value) {
      return value in ['UNKNOWN', 'EXCELLENT', 'GOOD', 'RISKY', 'BAD'];
    }

    match /sessions/{sessionId} {
      allow read: if signedIn();

      allow create, update: if signedIn()
        && request.resource.data.sessionId == sessionId
        && request.resource.data.keys().hasOnly([
          'sessionId',
          'deviceKey',
          'device',
          'context',
          'timestamps',
          'analysis',
          'sensorGroups'
        ])
        && request.resource.data.deviceKey is string
        && request.resource.data.device.manufacturer is string
        && request.resource.data.device.model is string
        && request.resource.data.device.sdkInt is int
        && request.resource.data.analysis.schemaVersion == 1
        && request.resource.data.analysis.totalRows is int
        && request.resource.data.analysis.durationS is number
        && request.resource.data.analysis.timingPassed is bool
        && request.resource.data.analysis.dataProblems is int
        && isValidVerdict(request.resource.data.analysis.verdict)
        && isValidVerdict(request.resource.data.analysis.rawVerdict);
    }

    match /deviceModels/{deviceKey} {
      allow read: if signedIn();

      allow create, update: if signedIn()
        && request.resource.data.deviceKey == deviceKey
        && request.resource.data.keys().hasOnly([
          'manufacturer',
          'model',
          'sdkInt',
          'deviceKey',
          'firstSeenAt',
          'lastSeenAt',
          'stats',
          'compatibility'
        ])
        && request.resource.data.manufacturer is string
        && request.resource.data.model is string
        && request.resource.data.sdkInt is int
        && request.resource.data.stats.sessionCount is int
        && request.resource.data.stats.passCount is int
        && request.resource.data.stats.warnCount is int
        && request.resource.data.stats.failCount is int
        && request.resource.data.stats.insufficientDataCount is int
        && request.resource.data.stats.avgCompleteness is number
        && request.resource.data.stats.avgJitterP95Ms is number
        && request.resource.data.stats.avgMedianDtMs is number
        && request.resource.data.stats.avgDurationS is number
        && request.resource.data.stats.totalGaps is int
        && request.resource.data.stats.totalWatchdogResurrections is int
        && request.resource.data.compatibility.score is int
        && isValidCategory(request.resource.data.compatibility.category)
        && isValidVerdict(request.resource.data.compatibility.lastVerdict)
        && isValidVerdict(request.resource.data.compatibility.lastRawVerdict);
    }
  }
}
```

Estas reglas sirven para desarrollo controlado. Para produccion real, lo mas robusto seria mover la actualizacion del agregado `deviceModels` a Cloud Functions o un backend, porque cualquier cliente autenticado anonimamente podria intentar manipular sus propios resultados.

## 7. Indices recomendados

Firestore te mostrara enlaces para crear indices cuando una consulta los necesite. Si quieres crearlos desde la consola:

1. Ve a `Firestore Database > Indexes`.
2. Pulsa `Create index`.
3. Crea estos indices compuestos.

Para `sessions`:

```text
deviceKey ASC, timestamps.analyzedAt DESC
device.manufacturer ASC, timestamps.analyzedAt DESC
analysis.verdict ASC, timestamps.analyzedAt DESC
```

Para `deviceModels`:

```text
compatibility.category ASC, compatibility.score DESC
manufacturer ASC, compatibility.score DESC
```

## 8. Verificacion

1. Pon `app/google-services.json` en su sitio.
2. Compila la app.
3. Ejecuta una sesion o usa una sesion existente.
4. Pulsa `Analizar`.
5. En Firebase Console, comprueba que aparecen:

```text
Firestore Database > Data > sessions
Firestore Database > Data > deviceModels
```

Si no aparece nada, revisa Logcat buscando:

```text
Upload a Firestore fallo
```

## 9. Ranking futuro

Para un front futuro, la pantalla principal deberia consultar `deviceModels`, no todas las sesiones.

Mejores dispositivos:

```text
deviceModels orderBy compatibility.score desc
```

Sesiones de un modelo:

```text
sessions where deviceKey == "<deviceKey>" orderBy timestamps.analyzedAt desc
```

Dispositivos problematicos:

```text
deviceModels where compatibility.category in ["RISKY", "BAD"] orderBy compatibility.score asc
```
