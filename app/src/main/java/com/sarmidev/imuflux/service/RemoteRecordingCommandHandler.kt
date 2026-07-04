package com.sarmidev.imuflux.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingCommand
import com.sarmidev.imuflux.recording.RecordingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes remote Start/Stop recording commands received via FCM by driving the
 * existing [RecordingService] the exact same way a local button press does.
 *
 * Design notes:
 *  - **Reuses the real pipeline**: START issues `RecordingService.ACTION_START`
 *    (with the forklift/warehouse from [SessionConfigStore]), so remote start
 *    behaves identically to local start — including marking the recording
 *    intention in [RecordingIntentStore] inside the service, which is what the
 *    auto-resume watchdog relies on.
 *  - **Idempotent**: uses the singleton [RecordingEngine.isRecording] state as
 *    the source of truth. A duplicate START while already recording, or a STOP
 *    while not recording, is a no-op — never an error and never a duplicate
 *    session.
 *  - **Background-start aware**: high-priority FCM data messages let the app run
 *    briefly in the background; we start the foreground service via
 *    [ContextCompat.startForegroundService] and log (rather than crash) if the
 *    OS refuses the background foreground-service start.
 */
@Singleton
class RemoteRecordingCommandHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingEngine: RecordingEngine,
    private val sessionConfigStore: SessionConfigStore,
) {

    fun handle(command: RemoteRecordingCommand, requestId: String?) {
        Log.i(TAG, "Remote command received: $command (requestId=$requestId)")
        when (command) {
            RemoteRecordingCommand.START_RECORDING -> startRecording(requestId)
            RemoteRecordingCommand.STOP_RECORDING -> stopRecording(requestId)
        }
    }

    private fun startRecording(requestId: String?) {
        if (recordingEngine.isRecording.value) {
            Log.i(TAG, "START ignored — already recording (requestId=$requestId)")
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORKLIFT, sessionConfigStore.getForklift())
            putExtra(RecordingService.EXTRA_WAREHOUSE, sessionConfigStore.getWarehouse())
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onSuccess { Log.i(TAG, "START dispatched to RecordingService (requestId=$requestId)") }
            .onFailure {
                // Android 12+ can throw ForegroundServiceStartNotAllowedException when
                // launching a foreground service from the background. High-priority
                // data messages usually grant a short allowance; log clearly if not.
                Log.e(TAG, "START failed — could not launch foreground service (requestId=$requestId)", it)
            }
    }

    private fun stopRecording(requestId: String?) {
        if (!recordingEngine.isRecording.value) {
            Log.i(TAG, "STOP ignored — not currently recording (requestId=$requestId)")
            return
        }
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
            .onSuccess { Log.i(TAG, "STOP dispatched to RecordingService (requestId=$requestId)") }
            .onFailure { Log.e(TAG, "STOP failed (requestId=$requestId)", it) }
    }

    private companion object {
        const val TAG = "RemoteRecCmdHandler"
    }
}
