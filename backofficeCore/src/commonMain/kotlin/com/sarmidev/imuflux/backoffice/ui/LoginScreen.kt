package com.sarmidev.imuflux.backoffice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    isLoggingIn: Boolean,
    error: String?,
    configSource: String?,
    onLogin: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val windowSize = currentWindowSize()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = contentPaddingFor(windowSize.widthClass)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ImuFlux Diagnostics", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Inicia sesión con una cuenta admin de Firebase (custom claim admin == true).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    enabled = !isLoggingIn,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardActions = KeyboardActions(onDone = { onLogin(email, password) }),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { onLogin(email, password) },
                    enabled = !isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoggingIn) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Iniciar sesión")
                    }
                }

                if (configSource != null) {
                    Text(
                        "Config Firebase: $configSource",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigErrorScreen(message: String) {
    val windowSize = currentWindowSize()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = contentPaddingFor(windowSize.widthClass)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Configuración de Firebase incompleta",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
