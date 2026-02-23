package org.cygnusx1.openbu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

private val PresetColors = listOf(
    "Red" to Color(0xFFF44336),
    "Pink" to Color(0xFFE91E63),
    "Purple" to Color(0xFF9C27B0),
    "Deep Purple" to Color(0xFF673AB7),
    "Indigo" to Color(0xFF3F51B5),
    "Blue" to Color(0xFF2196F3),
    "Light Blue" to Color(0xFF03A9F4),
    "Cyan" to Color(0xFF00BCD4),
    "Teal" to Color(0xFF009688),
    "Green" to Color(0xFF4CAF50),
    "Light Green" to Color(0xFF8BC34A),
    "Lime" to Color(0xFFCDDC39),
    "Yellow" to Color(0xFFFFEB3B),
    "Amber" to Color(0xFFFFC107),
    "Orange" to Color(0xFFFF9800),
    "Deep Orange" to Color(0xFFFF5722),
    "Brown" to Color(0xFF795548),
    "Blue Grey" to Color(0xFF607D8B),
    "Grey" to Color(0xFF9E9E9E),
    "White" to Color(0xFFFFFFFF),
    "Black" to Color(0xFF000000),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrinterSettingsScreen(
    customPrinterName: String,
    onCustomPrinterNameChanged: (String) -> Unit,
    rtspEnabled: Boolean,
    onRtspEnabledChanged: (Boolean) -> Unit,
    rtspUrl: String,
    onRtspUrlChanged: (String) -> Unit,
    customBgColor: Int?,
    onBgColorChanged: (Int?) -> Unit,
    isSaved: Boolean,
    onSavePrinter: () -> Unit,
    onDeletePrinter: () -> Unit,
    onBack: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove saved printer?") },
            text = { Text("This printer will no longer appear in your saved printers list.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDeletePrinter()
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showBgColorPicker) {
        ColorPickerDialog(
            title = "Background color",
            currentColor = customBgColor,
            onColorSelected = {
                onBgColorChanged(it)
                showBgColorPicker = false
            },
            onDismiss = { showBgColorPicker = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text("Printer Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        Column(modifier = Modifier.padding(16.dp)) {
            // Save printer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Save this printer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        if (isSaved) showDeleteConfirmation = true else onSavePrinter()
                    },
                ) {
                    Text(if (isSaved) "Saved" else "Save")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Custom printer name
            OutlinedTextField(
                value = customPrinterName,
                onValueChange = onCustomPrinterNameChanged,
                label = { Text("Printer name") },
                placeholder = { Text("My Printer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // RTSP stream toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "External RTSP stream",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Show an external RTSP camera on the dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rtspEnabled,
                    onCheckedChange = onRtspEnabledChanged,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // RTSP URL field
            OutlinedTextField(
                value = rtspUrl,
                onValueChange = onRtspUrlChanged,
                label = { Text("RTSP URL") },
                placeholder = { Text("rtsp://192.168.1.100:8554/stream") },
                singleLine = true,
                enabled = rtspEnabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Background color
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background color",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Custom background for this printer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (customBgColor != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(customBgColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showBgColorPicker = true },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onBgColorChanged(null) }) {
                        Text("Reset")
                    }
                } else {
                    Button(onClick = { showBgColorPicker = true }) {
                        Text("Pick")
                    }
                }
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    title: String,
    currentColor: Int?,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedColor by remember { mutableStateOf(currentColor) }
    var hexInput by remember {
        mutableStateOf(
            if (currentColor != null) String.format("#%06X", 0xFFFFFF and currentColor) else ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PresetColors.forEach { (name, color) ->
                        val argb = color.toArgb()
                        val isSelected = selectedColor == argb
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    selectedColor = argb
                                    hexInput = String.format("#%06X", 0xFFFFFF and argb)
                                },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input
                        val parsed = parseHexColor(input)
                        if (parsed != null) selectedColor = parsed
                    },
                    label = { Text("Hex color") },
                    placeholder = { Text("#FF5722") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedColor?.let { onColorSelected(it) } },
                enabled = selectedColor != null,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6) return null
    return try {
        (0xFF000000 or hex.toLong(16)).toInt()
    } catch (_: NumberFormatException) {
        null
    }
}
