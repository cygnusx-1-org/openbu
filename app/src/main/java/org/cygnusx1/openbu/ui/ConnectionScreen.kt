package org.cygnusx1.openbu.ui

import org.cygnusx1.openbu.data.PrinterSeries
import org.cygnusx1.openbu.data.printerSeriesFromSerial
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Bookmark
import org.cygnusx1.openbu.network.DiscoveredPrinter
import org.cygnusx1.openbu.network.SavedPrinter
import org.cygnusx1.openbu.viewmodel.ConnectionState

@Composable
fun ConnectionScreen(
    connectionState: ConnectionState,
    errorMessage: String?,
    discoveredPrinters: List<DiscoveredPrinter>,
    savedPrinters: List<SavedPrinter>,
    savePrinterDefault: Boolean = true,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onGetSavedAccessCode: (serialNumber: String) -> String,
    onConnect: (ip: String, accessCode: String, serialNumber: String, savePrinter: Boolean) -> Unit,
    onDeletePrinter: (serialNumber: String) -> Unit = {},
) {
    var ip by rememberSaveable { mutableStateOf("") }
    var accessCode by rememberSaveable { mutableStateOf("") }
    var accessCodeVisible by rememberSaveable { mutableStateOf(false) }
    var serialNumber by rememberSaveable { mutableStateOf("") }
    var savePrinter by rememberSaveable { mutableStateOf(savePrinterDefault) }
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var selectedSerial by rememberSaveable { mutableStateOf<String?>(null) }
    var printerToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val isConnecting = connectionState == ConnectionState.Connecting

    val textFieldColors = OutlinedTextFieldDefaults.colors()

    val isSerialLengthValid = serialNumber.length in 15..16
    val hasValidSerialPrefix = printerSeriesFromSerial(serialNumber) != PrinterSeries.UNKNOWN
    val serialValid = isSerialLengthValid && hasValidSerialPrefix

    val savedSerials = savedPrinters.map { it.serialNumber }.toSet()
    val filteredDiscovered = discoveredPrinters.filter { it.serialNumber !in savedSerials }

    val selectedPrinter = discoveredPrinters.firstOrNull { it.serialNumber == selectedSerial }
    val selectedSavedPrinter = savedPrinters.firstOrNull { it.serialNumber == selectedSerial }
    val accessCodeValid = accessCode.length == 8
    val canConnectAuto = (selectedPrinter != null || selectedSavedPrinter != null) && accessCodeValid
    val canConnectManual = ip.isNotBlank() && accessCodeValid && serialValid
    val canConnect = if (manualMode) canConnectManual else canConnectAuto

    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose { onStopDiscovery() }
    }

    printerToDelete?.let { serial ->
        val name = savedPrinters.firstOrNull { it.serialNumber == serial }
            ?.deviceName?.ifBlank { null } ?: "this printer"
        AlertDialog(
            onDismissRequest = { printerToDelete = null },
            title = { Text("Delete printer?") },
            text = { Text("Remove $name from saved printers?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePrinter(serial)
                    if (selectedSerial == serial) selectedSerial = null
                    printerToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { printerToDelete = null }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Openbu",
            style = TextStyle(fontSize = 32.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect to your Bambu Lab printers in Developer Mode.",
            style = TextStyle(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Manual entry",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Switch(
                checked = manualMode,
                onCheckedChange = { manualMode = it },
                enabled = !isConnecting,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (manualMode) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = { Text("Printer IP Address") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = accessCode,
                onValueChange = { accessCode = it.trim() },
                label = { Text("Access Code") },
                isError = accessCode.isNotBlank() && !accessCodeValid,
                supportingText = {
                    if (accessCode.isNotBlank() && !accessCodeValid) {
                        Text("Must be exactly 8 characters (currently ${accessCode.length})", color = Color.Red)
                    }
                },
                singleLine = true,
                colors = textFieldColors,
                visualTransformation = if (accessCodeVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { accessCodeVisible = !accessCodeVisible }) {
                        Icon(
                            imageVector = if (accessCodeVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (accessCodeVisible) "Hide access code" else "Show access code",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = serialNumber,
                onValueChange = { serialNumber = it.trim() },
                label = { Text("Printer Serial Number") },
                isError = serialNumber.isNotBlank() && !serialValid,
                supportingText = {
                    if (serialNumber.isNotBlank() && !serialValid) {
                        if (!isSerialLengthValid) {
                            Text("Must be 15 or 16 characters (currently ${serialNumber.length})", color = Color.Red)
                        } else {
                            Text("Unrecognized serial number prefix.", color = Color.Red)
                        }
                    }
                },
                singleLine = true,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canConnect) {
                            onConnect(ip, accessCode, serialNumber, savePrinter)
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting,
            )
        } else {
            // Auto-discovery mode
            if (savedPrinters.isEmpty() && discoveredPrinters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Searching for printers on your network...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Make sure your printer is on and Developer Mode is enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (savedPrinters.isNotEmpty()) {
                        item(key = "header_saved") {
                            Text(
                                text = "Saved Printers",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        items(savedPrinters, key = { "saved_${it.serialNumber}" }) { printer ->
                            PrinterCard(
                                name = printer.deviceName.ifBlank { "Bambu Lab Printer" },
                                detail = "${printer.ip} · ${printer.serialNumber}",
                                icon = Icons.Filled.Bookmark,
                                isSelected = selectedSerial == printer.serialNumber,
                                enabled = !isConnecting,
                                onClick = {
                                    selectedSerial = printer.serialNumber
                                    ip = printer.ip
                                    accessCode = printer.accessCode
                                    accessCodeVisible = false
                                },
                                onLongClick = { printerToDelete = printer.serialNumber },
                            )
                        }
                    }

                    if (filteredDiscovered.isNotEmpty()) {
                        item(key = "header_discovered") {
                            Text(
                                text = "Discovered Printers",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = if (savedPrinters.isNotEmpty()) 8.dp else 0.dp, bottom = 4.dp),
                            )
                        }
                    }

                    items(
                        filteredDiscovered.sortedByDescending { it.lastSeen },
                        key = { "disc_${it.serialNumber}" },
                    ) { printer ->
                        PrinterCard(
                            name = printer.deviceName.ifBlank { printer.modelCode.ifBlank { "Bambu Lab Printer" } },
                            detail = "${printer.ip} · ${printer.serialNumber}",
                            icon = Icons.Filled.Print,
                            isSelected = selectedSerial == printer.serialNumber,
                            enabled = !isConnecting,
                            onClick = {
                                selectedSerial = printer.serialNumber
                                accessCode = onGetSavedAccessCode(printer.serialNumber)
                                accessCodeVisible = false
                            },
                        )
                    }
                }
            }

            if (selectedPrinter != null || selectedSavedPrinter != null) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = accessCode,
                    onValueChange = { accessCode = it.trim() },
                    label = { Text("Access Code") },
                    isError = accessCode.isNotBlank() && !accessCodeValid,
                    supportingText = {
                        if (accessCode.isNotBlank() && !accessCodeValid) {
                            Text("Must be exactly 8 characters (currently ${accessCode.length})", color = Color.Red)
                        }
                    },
                    singleLine = true,
                    colors = textFieldColors,
                    visualTransformation = if (accessCodeVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { accessCodeVisible = !accessCodeVisible }) {
                            Icon(
                                imageVector = if (accessCodeVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (accessCodeVisible) "Hide access code" else "Show access code",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canConnect) {
                                if (selectedSavedPrinter != null) {
                                    onConnect(selectedSavedPrinter.ip, accessCode, selectedSavedPrinter.serialNumber, savePrinter)
                                } else if (selectedPrinter != null) {
                                    onConnect(selectedPrinter.ip, accessCode, selectedPrinter.serialNumber, savePrinter)
                                }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Save printer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Remember this printer for quick connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = savePrinter,
                onCheckedChange = { savePrinter = it },
                enabled = !isConnecting,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isConnecting) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    if (manualMode) {
                        onConnect(ip, accessCode, serialNumber, savePrinter)
                    } else if (selectedSavedPrinter != null) {
                        onConnect(selectedSavedPrinter.ip, accessCode, selectedSavedPrinter.serialNumber, savePrinter)
                    } else if (selectedPrinter != null) {
                        onConnect(selectedPrinter.ip, accessCode, selectedPrinter.serialNumber, savePrinter)
                    }
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage,
                color = Color.Red,
                style = TextStyle(fontSize = 14.sp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrinterCard(
    name: String,
    detail: String,
    icon: ImageVector,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    CardDefaults.shape,
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
