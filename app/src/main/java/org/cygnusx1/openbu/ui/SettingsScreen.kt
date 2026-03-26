package org.cygnusx1.openbu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private fun redactLogText(text: String, accessCode: String, serialNumber: String): String {
    var redacted = text
    if (accessCode.isNotEmpty()) {
        redacted = redacted.replace(accessCode, "REDACTED")
    }
    if (serialNumber.isNotEmpty()) {
        redacted = redacted.replace(serialNumber, "REDACTED")
    }
    // Redact passwords (e.g., "PASS a1b2c3d4")
    redacted = Regex("""(?<=PASS )\S+""").replace(redacted, "REDACTED")
    // Redact IP addresses (e.g., "10.0.0.1")
    redacted = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").replace(redacted, "REDACTED")
    // Redact PASV IP format (e.g., "(10,0,0,1,7,232)")
    redacted = Regex("""\(\d{1,3},\d{1,3},\d{1,3},\d{1,3},""").replace(redacted, "(REDACTED,")
    return redacted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keepConnectionInBackground: Boolean,
    onKeepConnectionChanged: (Boolean) -> Unit,
    showMainStream: Boolean,
    onShowMainStreamChanged: (Boolean) -> Unit,
    autoSavePrinter: Boolean,
    onAutoSavePrinterChanged: (Boolean) -> Unit,
    forceDarkMode: Boolean,
    onForceDarkModeChanged: (Boolean) -> Unit,
    useVlcForRtsp: Boolean,
    onUseVlcForRtspChanged: (Boolean) -> Unit,
    debugLogging: Boolean,
    onDebugLoggingChanged: (Boolean) -> Unit,
    redactLogs: Boolean,
    onRedactLogsChanged: (Boolean) -> Unit,
    mqttDataMessages: List<String>,
    logcatText: String,
    accessCode: String,
    serialNumber: String,
    onCaptureLogcat: () -> Unit,
    onBack: () -> Unit,
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    var showMqttDataDialog by remember { mutableStateOf(false) }
    var showLogcatDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Openbu") },
            text = {
                Text("Version $versionName")
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                IconButton(onClick = { showAboutDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "About",
                    )
                }
            },
        )

        Column(modifier = Modifier.padding(16.dp)) {
            // Persistent connection
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Persistent connection",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Maintain printer connection when the app is in the background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = keepConnectionInBackground,
                    onCheckedChange = onKeepConnectionChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Internal stream toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Internal stream",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Show the Bambu camera stream on the dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showMainStream,
                    onCheckedChange = onShowMainStreamChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-save printers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-save printers",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Default to saving printers on connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoSavePrinter,
                    onCheckedChange = onAutoSavePrinterChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Force dark mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Override device theme",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Use the opposite of the device's light/dark mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = forceDarkMode,
                    onCheckedChange = onForceDarkModeChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // VLC decoder toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use VLC decoder for RTSP",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Software decoder for devices that can't play high-res streams",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useVlcForRtsp,
                    onCheckedChange = onUseVlcForRtspChanged,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Debugging",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Debug logging toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug logging",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Log MQTT and camera stream details to Logcat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugLogging,
                    onCheckedChange = onDebugLoggingChanged,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Redact logs toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Redact logs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Redact sensitive data (passwords, IPs, serial numbers) when copying logs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = redactLogs,
                    onCheckedChange = onRedactLogsChanged,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MQTT data",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${mqttDataMessages.size} unique message structure(s) received",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showMqttDataDialog = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = "MQTT Data",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Logcat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Capture and view app logs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    onCaptureLogcat()
                    showLogcatDialog = true
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = "Logcat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showMqttDataDialog) {
        val bodyText = if (mqttDataMessages.isEmpty()) {
            "No messages received yet."
        } else {
            mqttDataMessages.joinToString("\n---\n")
        }

        AlertDialog(
            onDismissRequest = { showMqttDataDialog = false },
            title = { Text("${mqttDataMessages.size} unique message structure(s)") },
            text = {
                Text(
                    text = bodyText,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = if (redactLogs) {
                        Regex(""""(sn|ams_id|subtask_name)"\s*:\s*"[^"]*"""")
                            .replace(bodyText) { match ->
                                "\"${match.groupValues[1]}\": \"REDACTED\""
                            }
                    } else {
                        bodyText
                    }
                    clipboardManager.setText(AnnotatedString(text))
                }) {
                    Text(if (redactLogs) "Copy Redacted" else "Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMqttDataDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showLogcatDialog) {
        val rawText = logcatText.ifEmpty { "No logs captured yet." }
        val bodyText = if (redactLogs) {
            redactLogText(rawText, accessCode, serialNumber)
        } else {
            rawText
        }

        AlertDialog(
            onDismissRequest = { showLogcatDialog = false },
            title = { Text("Logcat") },
            text = {
                Text(
                    text = bodyText,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(bodyText))
                }) {
                    Text(if (redactLogs) "Copy Redacted" else "Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogcatDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}
