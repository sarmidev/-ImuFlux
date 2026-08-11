package com.sarmidev.imuflux.data.diagnostics

/**
 * Remote recording commands that the admin desktop can push to a device via
 * the `sendRemoteRecordingCommand` Cloud Function → FCM data message.
 *
 * The [wire] value is the canonical string used **everywhere**: the desktop
 * request body, the Cloud Function payload, the FCM data message, and the
 * Android message handler. Keeping it in `:shared` guarantees all layers agree.
 */
enum class RemoteRecordingCommand(val wire: String) {
    START_RECORDING("START_RECORDING"),
    STOP_RECORDING("STOP_RECORDING");

    companion object {
        /** Parses a [wire] value, returning null for unknown/invalid input. */
        fun fromWire(value: String?): RemoteRecordingCommand? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Canonical keys shared by the remote-command HTTP request body and the FCM
 * data payload. The Cloud Function copies [KEY_COMMAND]/[KEY_REQUEST_ID] and
 * adds [KEY_ISSUED_AT] into the data message; the Android handler reads them.
 */
object RemoteRecordingPayload {
    const val KEY_DEVICE_ID = "deviceId"
    const val KEY_COMMAND = "command"
    const val KEY_REQUEST_ID = "requestId"
    const val KEY_ISSUED_AT = "issuedAt"
}
