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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalConfiguration
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
fun DebuggingSettingsScreen(
    debugLogging: Boolean,
    onDebugLoggingChanged: (Boolean) -> Unit,
    redactLogs: Boolean,
    onRedactLogsChanged: (Boolean) -> Unit,
    mqttDataMessages: List<String>,
    logcatText: String,
    accessCode: String,
    serialNumber: String,
    onCaptureLogcat: (filter: String?) -> Unit,
    onBack: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    var showMqttDataDialog by remember { mutableStateOf(false) }
    var showLogcatDialog by remember { mutableStateOf(false) }
    var logDialogTitle by remember { mutableStateOf("Logs") }
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Device properties section
            Text(
                text = "Device properties",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Density DPI",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${configuration.densityDpi} dpi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            // Data section
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // MQTT data button
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showMqttDataDialog = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                    )
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
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Logs section
            Text(
                text = "Logs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // All logs button
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = debugLogging,
                onClick = {
                    onCaptureLogcat(null)
                    showLogcatDialog = true
                    logDialogTitle = "All Logs"
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "All logs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Capture and view all app logs",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (debugLogging) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Camera logs button
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = debugLogging,
                onClick = {
                    onCaptureLogcat("BambuCamera")
                    showLogcatDialog = true
                    logDialogTitle = "Camera Logs"
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Camera logs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Filter: BambuCamera",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (debugLogging) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // MQTT logs button
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = debugLogging,
                onClick = {
                    onCaptureLogcat("BambuMqtt")
                    showLogcatDialog = true
                    logDialogTitle = "MQTT Logs"
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MQTT logs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Filter: BambuMqtt",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (debugLogging) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // FTPS logs button
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = debugLogging,
                onClick = {
                    onCaptureLogcat("BambuFtps")
                    showLogcatDialog = true
                    logDialogTitle = "FTPS Logs"
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FTPS logs",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (debugLogging) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Filter: BambuFtps",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (debugLogging) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
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
            title = { Text(logDialogTitle) },
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
