/**
 * set-admin-claim.js
 *
 * Asigna el custom claim `admin: true` a un usuario de Firebase Auth.
 * Se ejecuta una sola vez (o para revocar/modificar claims posteriores).
 *
 * Uso:
 *   node scripts/set-admin-claim.js <email@example.com>
 *
 * Autenticación (elige una opción):
 *
 *   Opción A — Application Default Credentials (recomendado):
 *     gcloud auth application-default login
 *     node scripts/set-admin-claim.js admin@example.com
 *
 *   Opción B — Service Account descargado localmente (NO lo commits al repo):
 *     export GOOGLE_APPLICATION_CREDENTIALS="/ruta/local/service-account.json"
 *     node scripts/set-admin-claim.js admin@example.com
 *
 * Dependencia de ejecución única (no necesita estar en package.json del proyecto):
 *   npm install firebase-admin   # o npx -y firebase-admin ...
 *
 * ⚠ NUNCA commitas el service account .json al repositorio.
 *   Está excluido en .gitignore con el patrón *serviceAccount*.json
 */

"use strict";

const admin = require("firebase-admin");

const email = process.argv[2];
if (!email || !email.includes("@")) {
  console.error("Uso: node scripts/set-admin-claim.js <email@example.com>");
  process.exit(1);
}

// Inicializa con las credenciales del entorno (ADC o GOOGLE_APPLICATION_CREDENTIALS).
// No se pasa serviceAccount directamente para evitar que acabe en código fuente.
admin.initializeApp();

(async () => {
  try {
    const user = await admin.auth().getUserByEmail(email);
    await admin.auth().setCustomUserClaims(user.uid, { admin: true });
    console.log(`✅  Claim admin:true asignado a ${email} (uid: ${user.uid})`);
    console.log(
      "   El usuario debe cerrar sesión y volver a iniciarla para que el ID token " +
        "refleje el claim actualizado.",
    );
  } catch (err) {
    console.error("❌  Error al asignar el claim:", err.message);
    process.exit(1);
  }
})();
