package com.sarmidev.imuflux.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sarmidev.imuflux.data.diagnostics.FcmTokenRegistrar
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingCommand
import com.sarmidev.imuflux.data.diagnostics.RemoteRecordingPayload
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives FCM messages for remote recording control.
 *
 * Two responsibilities:
 *  - [onNewToken]: persist the refreshed token via [FcmTokenRegistrar] so the
 *    backend can always reach the current device.
 *  - [onMessageReceived]: parse the data payload and delegate Start/Stop to
 *    [RemoteRecordingCommandHandler].
 *
 * The backend only ever sends **data messages** (no `notification` block) with a
 * high priority, so `onMessageReceived` fires even in the background.
 */
@AndroidEntryPoint
class RemoteRecordingMessagingService : FirebaseMessagingService() {

    @Inject lateinit var tokenRegistrar: FcmTokenRegistrar
    @Inject lateinit var commandHandler: RemoteRecordingCommandHandler

    override fun onNewToken(token: String) {
        Log.d(TAG, "onNewToken")
        tokenRegistrar.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val commandWire = data[RemoteRecordingPayload.KEY_COMMAND]
        val command = RemoteRecordingCommand.fromWire(commandWire)
        if (command == null) {
            Log.w(TAG, "Ignoring message with unknown/missing command: $commandWire")
            return
        }
        val requestId = data[RemoteRecordingPayload.KEY_REQUEST_ID]
        runCatching { commandHandler.handle(command, requestId) }
            .onFailure { Log.e(TAG, "Failed to handle remote command $command", it) }
    }

    private companion object {
        const val TAG = "RemoteRecMsgService"
    }
}
