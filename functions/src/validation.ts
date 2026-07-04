/**
 * Pure request/payload validation for the remote recording command endpoint.
 *
 * Kept free of any Firebase/Express types so it can be unit-tested in isolation
 * with the built-in `node --test` runner (see validation.test.ts).
 *
 * The command wire values MUST stay in sync with the Kotlin
 * `RemoteRecordingCommand` enum in `:shared`.
 */

export const VALID_COMMANDS = ["START_RECORDING", "STOP_RECORDING"] as const;

export type RemoteRecordingCommand = (typeof VALID_COMMANDS)[number];

export interface RemoteCommandRequest {
  deviceId: string;
  command: RemoteRecordingCommand;
  /** Optional idempotency id echoed to the device in the FCM data payload. */
  requestId?: string;
}

export type ValidationResult =
  | { ok: true; value: RemoteCommandRequest }
  | { ok: false; error: string };

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

export function isValidCommand(value: unknown): value is RemoteRecordingCommand {
  return typeof value === "string" &&
    (VALID_COMMANDS as readonly string[]).includes(value);
}

/**
 * Validates the JSON body of a `sendRemoteRecordingCommand` request.
 *
 * Rules:
 *  - `deviceId`: required, non-empty string.
 *  - `command`: required, one of {@link VALID_COMMANDS}.
 *  - `requestId`: optional; when present must be a non-empty string.
 */
export function validateCommandRequest(body: unknown): ValidationResult {
  if (typeof body !== "object" || body === null) {
    return { ok: false, error: "Request body must be a JSON object." };
  }
  const b = body as Record<string, unknown>;

  if (!isNonEmptyString(b.deviceId)) {
    return { ok: false, error: "Field 'deviceId' is required and must be a non-empty string." };
  }
  if (!isValidCommand(b.command)) {
    return {
      ok: false,
      error: `Field 'command' must be one of: ${VALID_COMMANDS.join(", ")}.`,
    };
  }
  if (b.requestId !== undefined && !isNonEmptyString(b.requestId)) {
    return { ok: false, error: "Field 'requestId', when present, must be a non-empty string." };
  }

  return {
    ok: true,
    value: {
      deviceId: b.deviceId.trim(),
      command: b.command,
      requestId: isNonEmptyString(b.requestId) ? b.requestId.trim() : undefined,
    },
  };
}
