/**
 * ImuFlux Cloud Functions.
 *
 * `sendRemoteRecordingCommand` is the only endpoint: it lets an authenticated
 * **admin** operator (the desktop dashboard) push a Start/Stop recording command
 * to a specific device via FCM, without the desktop ever holding an FCM server
 * key or a service account.
 *
 * Security model:
 *   - Caller must present `Authorization: Bearer <Firebase ID token>`.
 *   - The token is verified with the Admin SDK and must carry `admin === true`.
 *   - The device's FCM token is read server-side from
 *     `diagnosticsDevices/{deviceId}.fcmToken` — never trusted from the client.
 *
 * No credentials are embedded: on Cloud Functions the Admin SDK initializes from
 * the runtime's Application Default Credentials.
 */
import { onRequest, Request } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import * as admin from "firebase-admin";
import type { Response } from "express";
import { validateCommandRequest } from "./validation";

admin.initializeApp();

const DEVICES_COLLECTION = "diagnosticsDevices";
const FIELD_FCM_TOKEN = "fcmToken";
const FIELD_FCM_TOKEN_INVALID_AT = "fcmTokenInvalidAt";

/** FCM error codes that mean the stored token is permanently unusable. */
const UNRECOVERABLE_TOKEN_ERRORS = new Set<string>([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

interface CommandResult {
  success: boolean;
  deviceId?: string;
  command?: string;
  messageId?: string;
  error?: string;
}

function sendJson(res: Response, status: number, body: CommandResult): void {
  res.status(status).json(body);
}

/**
 * Extracts and verifies the admin ID token from the Authorization header.
 * Returns the decoded token on success, or null after writing an error response.
 */
async function requireAdmin(req: Request, res: Response): Promise<admin.auth.DecodedIdToken | null> {
  const header = req.get("Authorization") || req.get("authorization") || "";
  const match = header.match(/^Bearer (.+)$/);
  if (!match) {
    sendJson(res, 401, { success: false, error: "Missing or malformed Authorization header." });
    return null;
  }
  const idToken = match[1].trim();
  let decoded: admin.auth.DecodedIdToken;
  try {
    decoded = await admin.auth().verifyIdToken(idToken);
  } catch (e) {
    logger.warn("ID token verification failed", { message: (e as Error).message });
    sendJson(res, 401, { success: false, error: "Invalid or expired authentication token." });
    return null;
  }
  if (decoded.admin !== true) {
    logger.warn("Non-admin caller rejected", { uid: decoded.uid });
    sendJson(res, 403, { success: false, error: "Caller is not an admin." });
    return null;
  }
  return decoded;
}

export const sendRemoteRecordingCommand = onRequest(
  { region: "europe-west1", cors: true },
  async (req: Request, res: Response): Promise<void> => {
    if (req.method === "OPTIONS") {
      res.status(204).send("");
      return;
    }
    if (req.method !== "POST") {
      sendJson(res, 405, { success: false, error: "Only POST is supported." });
      return;
    }

    const adminToken = await requireAdmin(req, res);
    if (!adminToken) return;

    const validation = validateCommandRequest(req.body);
    if (!validation.ok) {
      sendJson(res, 400, { success: false, error: validation.error });
      return;
    }
    const { deviceId, command, requestId } = validation.value;

    const db = admin.firestore();
    const deviceRef = db.collection(DEVICES_COLLECTION).doc(deviceId);
    const snap = await deviceRef.get();
    if (!snap.exists) {
      sendJson(res, 404, { success: false, deviceId, command, error: "Device document not found." });
      return;
    }
    const fcmToken = snap.get(FIELD_FCM_TOKEN) as string | undefined;
    if (!fcmToken) {
      sendJson(res, 409, {
        success: false,
        deviceId,
        command,
        error: "Device has no registered FCM token (remote control unavailable).",
      });
      return;
    }

    const issuedAt = Date.now().toString();
    const effectiveRequestId = requestId ?? `${deviceId}-${issuedAt}`;

    try {
      const messageId = await admin.messaging().send({
        token: fcmToken,
        data: {
          command,
          requestId: effectiveRequestId,
          issuedAt,
        },
        android: {
          priority: "high",
        },
      });
      logger.info("Remote command delivered", { deviceId, command, messageId, requestId: effectiveRequestId });
      sendJson(res, 200, { success: true, deviceId, command, messageId });
    } catch (e) {
      const code = (e as { code?: string }).code || "unknown";
      const message = (e as Error).message || "FCM send failed.";
      logger.error("FCM send failed", { deviceId, command, code, message });

      if (UNRECOVERABLE_TOKEN_ERRORS.has(code)) {
        // Mark the token invalid and clear it so the dashboard stops offering
        // remote control until the device re-registers a fresh token.
        await deviceRef
          .set(
            {
              [FIELD_FCM_TOKEN]: admin.firestore.FieldValue.delete(),
              [FIELD_FCM_TOKEN_INVALID_AT]: Date.now(),
            },
            { merge: true },
          )
          .catch((cleanupErr) =>
            logger.error("Failed to clear invalid token", { deviceId, message: (cleanupErr as Error).message }),
          );
        sendJson(res, 410, {
          success: false,
          deviceId,
          command,
          error: "Device FCM token is no longer valid; it has been cleared.",
        });
        return;
      }

      sendJson(res, 502, { success: false, deviceId, command, error: `FCM send failed: ${message}` });
    }
  },
);
