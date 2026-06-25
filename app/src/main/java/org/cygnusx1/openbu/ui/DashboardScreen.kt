package org.cygnusx1.openbu.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import org.cygnusx1.openbu.data.PrinterSeries
import org.cygnusx1.openbu.data.printerSeriesFromSerial
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.data.FilamentProfile
import org.cygnusx1.openbu.network.AmsTray
import org.cygnusx1.openbu.network.AmsUnit
import org.cygnusx1.openbu.data.HmsLookup
import org.cygnusx1.openbu.data.HmsWikiResult
import org.cygnusx1.openbu.network.PrinterStatus

private val HorizontalCardPadding = 4.dp
private val VerticalCardPadding = 4.dp

@Composable
fun DashboardScreen(
    frame: Bitmap?,
    fps: Float,
    isLightOn: Boolean?,
    isMqttConnected: Boolean,
    printerStatus: PrinterStatus,
    printerName: String,
    serialNumber: String,
    showMainStream: Boolean,
    internalRtspPlayer: ExoPlayer?,
    rtspPlayer: ExoPlayer?,
    externalVlcContent: (@Composable (Modifier) -> Unit)? = null,
    isReconnecting: Boolean = false,
    mjpegCameraFailed: Boolean = false,
    noRouteToHost: String? = null,
    onToggleLight: (Boolean) -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenInternalRtspFullscreen: () -> Unit,
    onOpenRtspFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrinterSettings: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenTimelapse: () -> Unit,
    onOpenSkipObjects: () -> Unit,
    onSetSpeedLevel: (Int) -> Unit,
    onSetNozzleTemperature: (Int) -> Unit,
    onSetBedTemperature: (Int) -> Unit,
    onSetFanSpeed: (Int, Int) -> Unit,
    onPrinterActionCommand: (String) -> Unit,
    filaments: List<FilamentProfile> = emptyList(),
    onSetFilament: (Int, Int, FilamentProfile, String) -> Unit = { _, _, _, _ -> },
    relayEnabled: Boolean = false,
    onRelayEnabledChanged: (Boolean) -> Unit = {},
    isRelayed: Boolean = false,
) {
    val context = LocalContext.current
    val series = printerSeriesFromSerial(serialNumber)
    val isEnclosed = series.isEnclosed
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showHmsDialog by remember { mutableStateOf(false) }
    var showNozzleDialog by remember { mutableStateOf(false) }
    var showBedDialog by remember { mutableStateOf(false) }
    var showPartFanDialog by remember { mutableStateOf(false) }
    var showAuxFanDialog by remember { mutableStateOf(false) }
    var showChamberFanDialog by remember { mutableStateOf(false) }
    var filamentEditTarget by remember { mutableStateOf<FilamentEditTarget?>(null) }

    filamentEditTarget?.let { target ->
        FilamentEditDialog(
            currentType = target.type,
            currentColor = target.color,
            currentTrayInfoIdx = target.trayInfoIdx,
            filaments = filaments,
            onConfirm = { profile, colorHex ->
                onSetFilament(target.amsId, target.trayId, profile, colorHex)
                filamentEditTarget = null
            },
            onDismiss = { filamentEditTarget = null },
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    if (showNozzleDialog) {
        TemperatureDialog(
            title = stringResource(R.string.nozzle_temperature),
            presetKey = "nozzle_presets",
            currentTemp = printerStatus.nozzleTargetTemper.toInt(),
            maxTemp = series.maxNozzleTemp,
            onDismiss = { showNozzleDialog = false },
            onConfirm = { temp ->
                onSetNozzleTemperature(temp)
                showNozzleDialog = false
            },
        )
    }

    if (showBedDialog) {
        TemperatureDialog(
            title = stringResource(R.string.bed_temperature),
            presetKey = "bed_presets",
            currentTemp = printerStatus.bedTargetTemper.toInt(),
            maxTemp = series.maxBedTemp,
            onDismiss = { showBedDialog = false },
            onConfirm = { temp ->
                onSetBedTemperature(temp)
                showBedDialog = false
            },
        )
    }

    if (showPartFanDialog) {
        Log.d("FanControl", "Part fan dialog opened: currentPwm=${printerStatus.coolingFanSpeed} (${kotlin.math.round(printerStatus.coolingFanSpeed * 100f / 255f).toInt()}%)")
        FanSpeedDialog(
            title = stringResource(R.string.part_fan_speed),
            presetKey = "part_fan_presets",
            currentSpeedPwm = printerStatus.coolingFanSpeed,
            onDismiss = { showPartFanDialog = false },
            onConfirm = { percent ->
                val pwm = kotlin.math.round(percent * 255f / 100f).toInt()
                Log.d("FanControl", "Part fan confirm: percent=$percent -> pwm=$pwm (fan=1)")
                onSetFanSpeed(1, pwm)
                showPartFanDialog = false
            },
        )
    }

    if (showAuxFanDialog) {
        Log.d("FanControl", "Aux fan dialog opened: currentPwm=${printerStatus.bigFan1Speed} (${kotlin.math.round(printerStatus.bigFan1Speed * 100f / 255f).toInt()}%)")
        FanSpeedDialog(
            title = stringResource(R.string.aux_fan_speed),
            presetKey = "aux_fan_presets",
            currentSpeedPwm = printerStatus.bigFan1Speed,
            onDismiss = { showAuxFanDialog = false },
            onConfirm = { percent ->
                val pwm = kotlin.math.round(percent * 255f / 100f).toInt()
                Log.d("FanControl", "Aux fan confirm: percent=$percent -> pwm=$pwm (fan=2)")
                onSetFanSpeed(2, pwm)
                showAuxFanDialog = false
            },
        )
    }

    if (showChamberFanDialog) {
        Log.d("FanControl", "Chamber fan dialog opened: currentPwm=${printerStatus.bigFan2Speed} (${kotlin.math.round(printerStatus.bigFan2Speed * 100f / 255f).toInt()}%)")
        FanSpeedDialog(
            title = stringResource(R.string.chamber_fan_speed),
            presetKey = "chamber_fan_presets",
            currentSpeedPwm = printerStatus.bigFan2Speed,
            onDismiss = { showChamberFanDialog = false },
            onConfirm = { percent ->
                val pwm = kotlin.math.round(percent * 255f / 100f).toInt()
                Log.d("FanControl", "Chamber fan confirm: percent=$percent -> pwm=$pwm (fan=3)")
                onSetFanSpeed(3, pwm)
                showChamberFanDialog = false
            },
        )
    }

    if (showHmsDialog) {
        AlertDialog(
            onDismissRequest = { showHmsDialog = false },
            title = { Text(stringResource(R.string.hms_errors)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    printerStatus.hmsErrors.forEach { err ->
                        val result = HmsLookup.resolve(context, err.hmsCode, series)
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = err.hmsCode,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                if (result is HmsWikiResult.NoMatch && result.availablePaths.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.available_paths, result.availablePaths.joinToString(", ")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (result is HmsWikiResult.Match) {
                                TextButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW,
                                            Uri.parse(result.url))
                                        context.startActivity(intent)
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(stringResource(R.string.view_on_bambu_wiki))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHmsDialog = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    if (showSpeedDialog) {
        SpeedLevelDialog(
            currentLevel = printerStatus.spdLvl,
            onDismiss = { showSpeedDialog = false },
            onConfirm = { level ->
                onSetSpeedLevel(level)
                showSpeedDialog = false
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(modifier = Modifier.height(VerticalCardPadding))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (printerName.isNotBlank()) {
                    Text(
                        text = printerName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.back_to_connections)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onDisconnect()
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.reconnect)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onReconnect()
                    },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.printer_settings)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenPrinterSettings()
                    },
                    icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.file_manager)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenFileManager()
                    },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.timelapses)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenTimelapse()
                    },
                    icon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.skip_objects)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSkipObjects()
                    },
                    icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remote_relay),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = relayEnabled,
                        onCheckedChange = onRelayEnabledChanged,
                    )
                }
            }
        },
    ) {
    Scaffold { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.cd_menu),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (isReconnecting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                if (printerName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = printerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (noRouteToHost != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = noRouteToHost,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // We only make a single connection attempt, so surface a Retry button right
                    // here once it fails rather than leaving it buried in the navigation drawer.
                    if (!isReconnecting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = onReconnect) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                if (mjpegCameraFailed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.camera_two_connection_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (printerStatus.hmsErrors.isNotEmpty()) {
                    TextButton(onClick = { showHmsDialog = true }) {
                        Text(
                            text = stringResource(R.string.hms_error_label),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(onClick = { showSpeedDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = stringResource(R.string.print_speed),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // Mini video preview
        if (showMainStream && internalRtspPlayer == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        .clickable { onOpenFullscreen() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame != null) {
                        Image(
                            bitmap = frame.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_camera_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }

                    // FPS badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.fps, fps),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }

                    // Relayed badge
                    if (isRelayed) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.relayed),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        // Internal RTSP stream (non-P1 printer built-in camera) — always ExoPlayer
        if (showMainStream && internalRtspPlayer != null) {
            RtspStreamCard(player = internalRtspPlayer, onClick = onOpenInternalRtspFullscreen, isRelayed = isRelayed)
            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        // External RTSP stream
        if (rtspPlayer != null) {
            RtspStreamCard(player = rtspPlayer, onClick = onOpenRtspFullscreen, isRelayed = isRelayed)
        } else if (externalVlcContent != null) {
            VlcRtspStreamCard(vlcContent = externalVlcContent, onClick = onOpenRtspFullscreen)
            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        // Chamber light control
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalCardPadding, vertical = VerticalCardPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chamber_light),
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (isMqttConnected && isLightOn == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = isLightOn == true,
                        onCheckedChange = { onToggleLight(it) },
                        enabled = isMqttConnected && isLightOn != null,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(VerticalCardPadding))

        // Status
        PrintStatusCard(
            printerStatus = printerStatus,
            onPrinterActionCommand = onPrinterActionCommand,
        )

        Spacer(modifier = Modifier.height(VerticalCardPadding))

        // Nozzle, Bed & Fan speeds
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStatusCard(
                title = stringResource(R.string.nozzle),
                iconRes = R.drawable.ic_nozzle,
                value = stringResource(R.string.temp_current_target, printerStatus.nozzleTemper, printerStatus.nozzleTargetTemper),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { showNozzleDialog = true },
            )
            IconStatusCard(
                title = stringResource(R.string.bed),
                iconRes = R.drawable.ic_bed,
                value = stringResource(R.string.temp_current_target, printerStatus.bedTemper, printerStatus.bedTargetTemper),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { showBedDialog = true },
            )
            IconStatusCard(
                title = stringResource(R.string.part_fan),
                iconRes = R.drawable.ic_part_fan,
                value = stringResource(R.string.percent, fanSpeedPercent(printerStatus.coolingFanSpeed)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { showPartFanDialog = true },
            )
            if (isEnclosed) {
                IconStatusCard(
                    title = stringResource(R.string.aux_fan),
                    iconRes = R.drawable.ic_aux_fan,
                    value = stringResource(R.string.percent, fanSpeedPercent(printerStatus.bigFan1Speed)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { showAuxFanDialog = true },
                )
                IconStatusCard(
                    title = stringResource(R.string.chamber_fan),
                    iconRes = R.drawable.ic_chamber_fan,
                    value = stringResource(R.string.percent, fanSpeedPercent(printerStatus.bigFan2Speed)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { showChamberFanDialog = true },
                )
            }
        }

        Spacer(modifier = Modifier.height(VerticalCardPadding))

        // AMS cards — full-width AMS, half-width AMS-HT + External Spool
        val fullWidthAms = printerStatus.amsUnits.filter { it.model.isEmpty() || it.model.equals("AMS", ignoreCase = true) }
        val halfWidthAms = printerStatus.amsUnits.filter { it.model.isNotEmpty() && !it.model.equals("AMS", ignoreCase = true) }

        for (amsUnit in fullWidthAms) {
            AmsCard(amsUnit) { tray ->
                filamentEditTarget = FilamentEditTarget(
                    amsId = amsUnit.id.toIntOrNull() ?: 0,
                    trayId = tray.id.toIntOrNull() ?: 0,
                    type = tray.trayType,
                    color = tray.trayColor,
                    trayInfoIdx = tray.trayInfoIdx,
                )
            }
            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        // Half-width items: AMS-HT units + External Spool, paired into rows
        var halfIndex = 0
        while (halfIndex < halfWidthAms.size) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(HorizontalCardPadding),
            ) {
                val unit = halfWidthAms[halfIndex]
                AmsCard(unit, modifier = Modifier.weight(1f).fillMaxHeight()) { tray ->
                    filamentEditTarget = FilamentEditTarget(
                        amsId = unit.id.toIntOrNull() ?: 0,
                        trayId = tray.id.toIntOrNull() ?: 0,
                        type = tray.trayType,
                        color = tray.trayColor,
                        trayInfoIdx = tray.trayInfoIdx,
                    )
                }
                halfIndex++
                if (halfIndex < halfWidthAms.size) {
                    val unit2 = halfWidthAms[halfIndex]
                    AmsCard(unit2, modifier = Modifier.weight(1f).fillMaxHeight()) { tray ->
                        filamentEditTarget = FilamentEditTarget(
                            amsId = unit2.id.toIntOrNull() ?: 0,
                            trayId = tray.id.toIntOrNull() ?: 0,
                            type = tray.trayType,
                            color = tray.trayColor,
                            trayInfoIdx = tray.trayInfoIdx,
                        )
                    }
                    halfIndex++
                } else {
                    // Pair last AMS-HT with External Spool
                    ExternalSpoolCard(printerStatus.vtTray, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        filamentEditTarget = FilamentEditTarget(
                            amsId = 255,
                            trayId = 254,
                            type = printerStatus.vtTray?.trayType ?: "",
                            color = printerStatus.vtTray?.trayColor ?: "",
                            trayInfoIdx = printerStatus.vtTray?.trayInfoIdx ?: "",
                        )
                    }
                    halfIndex++ // signal we used external spool
                }
            }
            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        // External spool — full-width if not already paired above
        if (halfWidthAms.size % 2 == 0) {
            ExternalSpoolCard(printerStatus.vtTray) {
                filamentEditTarget = FilamentEditTarget(
                    amsId = 255,
                    trayId = 254,
                    type = printerStatus.vtTray?.trayType ?: "",
                    color = printerStatus.vtTray?.trayColor ?: "",
                    trayInfoIdx = printerStatus.vtTray?.trayInfoIdx ?: "",
                )
            }
            Spacer(modifier = Modifier.height(VerticalCardPadding))
        }

        Spacer(modifier = Modifier.height(32.dp))
        } // scrollable Column
    }
    } // Scaffold
    } // ModalNavigationDrawer
}

@Composable
private fun PrintStatusCard(
    printerStatus: PrinterStatus,
    onPrinterActionCommand: (String) -> Unit,
) {
    val stateLabel = when (printerStatus.gcodeState) {
        "RUNNING" -> "Printing"
        "PAUSE" -> "Paused"
        "FINISH" -> "Finished"
        "FAILED" -> "Failed"
        "IDLE" -> "Idle"
        "PREPARE" -> "Preparing"
        else -> printerStatus.gcodeState
    }

    val remainingMin = printerStatus.mcRemainingTime
    val percent = printerStatus.mcPercent
    val totalMin = if (percent > 0) {
        (remainingMin / (1f - percent / 100f)).toInt()
    } else 0

    fun formatTime(minutes: Int): String = when {
        minutes >= 60 -> "%dh %dm".format(minutes / 60, minutes % 60)
        minutes > 0 -> "%dm".format(minutes)
        else -> ""
    }

    val showEta = printerStatus.gcodeState != "FAILED"

    val etaText = if (showEta && remainingMin > 0) {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, remainingMin) }
        val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
        "(ETA ${fmt.format(cal.time).lowercase()})"
    } else ""

    val timeText = when {
        showEta && remainingMin > 0 && totalMin > 0 -> "${formatTime(remainingMin)} / ${formatTime(totalMin)} $etaText"
        showEta && remainingMin > 0 -> "${formatTime(remainingMin)} $etaText"
        else -> ""
    }

    val fileName = printerStatus.gcodeFile.substringAfterLast("/")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HorizontalCardPadding, vertical = VerticalCardPadding),
        ) {
            // State + remaining time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (timeText.isNotEmpty()) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Filename
            if (fileName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                LowDpiScaledContent {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // Progress bar with percentage and layer info
            if (printerStatus.gcodeState == "RUNNING" || printerStatus.gcodeState == "PAUSE" || printerStatus.gcodeState == "FINISH") {
                Spacer(modifier = Modifier.height(10.dp))
                val progressFraction = (percent / 100f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    // Filled portion
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    // Labels on top
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.percent, percent),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = stringResource(R.string.layer_progress, printerStatus.layerNum, printerStatus.totalLayerNum),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Pause / Resume / Stop buttons
                if (printerStatus.gcodeState != "FINISH") {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (printerStatus.gcodeState == "RUNNING") {
                            FilledTonalButton(
                                onClick = { onPrinterActionCommand("pause") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = stringResource(R.string.pause),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.pause))
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { onPrinterActionCommand("resume") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.resume),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.resume))
                            }
                        }
                        FilledTonalButton(
                            onClick = { onPrinterActionCommand("stop") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.stop),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.stop))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconStatusCard(
    title: String,
    iconRes: Int,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalCardPadding, vertical = VerticalCardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AmsCard(amsUnit: AmsUnit, modifier: Modifier = Modifier, onTrayClick: (AmsTray) -> Unit) {
    val amsLabel = amsUnit.model.ifEmpty { "AMS" }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.ams_temp_humidity, amsLabel, amsUnit.temp, amsUnit.humidity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (amsUnit.trays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (tray in amsUnit.trays) {
                        FilamentSlot(tray, onClick = { onTrayClick(tray) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LowDpiScaledContent(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, current.fontScale * 0.75f)
    ) { content() }
}

@Composable
private fun FilamentSlot(tray: AmsTray, onClick: () -> Unit = {}) {
    val isEmpty = tray.trayType.isEmpty()
    LowDpiScaledContent {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onClick() },
        ) {
            if (isEmpty) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                    )
                }
            } else {
                val bgColor = parseHexColor(tray.trayColor)
                val grams = tray.remainGrams
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    if (grams != null) {
                        Text(
                            text = stringResource(R.string.grams_value, grams),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contrastTextColor(bgColor),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tray.trayType,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ExternalSpoolCard(vtTray: AmsTray?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.external_spool),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (vtTray != null) {
                    FilamentSlot(vtTray, onClick = onClick)
                } else {
                    LowDpiScaledContent {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onClick() },
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.empty),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RtspStreamCard(player: ExoPlayer, onClick: () -> Unit, isRelayed: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
    ) {
        Box {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            )

            if (isRelayed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.relayed),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private val FilamentPresetColors = listOf(
    "White" to Color(0xFFFFFFFF),
    "Black" to Color(0xFF000000),
    "Red" to Color(0xFFF44336),
    "Orange" to Color(0xFFFF9800),
    "Yellow" to Color(0xFFFFEB3B),
    "Green" to Color(0xFF4CAF50),
    "Blue" to Color(0xFF2196F3),
    "Purple" to Color(0xFF9C27B0),
    "Pink" to Color(0xFFE91E63),
    "Brown" to Color(0xFF795548),
    "Grey" to Color(0xFF9E9E9E),
    "Cyan" to Color(0xFF00BCD4),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilamentEditDialog(
    currentType: String,
    currentColor: String,
    currentTrayInfoIdx: String,
    filaments: List<FilamentProfile>,
    onConfirm: (FilamentProfile, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = remember(filaments) { filaments.map { it.type }.distinct().sorted() }
    val initialProfile = remember { filaments.find { it.filamentId == currentTrayInfoIdx } }
    var selectedType by remember {
        mutableStateOf(initialProfile?.type ?: currentType.ifEmpty { types.firstOrNull() ?: "" })
    }
    var selectedProfile by remember { mutableStateOf(initialProfile) }
    var selectedColorArgb by remember {
        mutableStateOf(
            if (currentColor.length >= 6) {
                try {
                    val r = currentColor.substring(0, 2).toInt(16)
                    val g = currentColor.substring(2, 4).toInt(16)
                    val b = currentColor.substring(4, 6).toInt(16)
                    Color(android.graphics.Color.argb(255, r, g, b)).toArgb()
                } catch (_: Exception) { Color.Gray.toArgb() }
            } else Color.Gray.toArgb()
        )
    }
    var hexInput by remember {
        mutableStateOf(
            if (currentColor.length >= 6) "#${currentColor.take(6)}" else ""
        )
    }

    val filtered = remember(filaments, selectedType) {
        filaments.filter { it.type == selectedType }.sortedBy { it.name }
    }

    // Auto-select first profile when type changes (but not on initial load if we matched by trayInfoIdx)
    if (selectedProfile == null || selectedProfile!!.type != selectedType) {
        selectedProfile = filtered.firstOrNull()
    }

    var typeExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_filament)) },
        text = {
            Column {
                // Type dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        for (type in types) {
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    selectedType = type
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Filament dropdown
                ExposedDropdownMenuBox(
                    expanded = profileExpanded,
                    onExpandedChange = { profileExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProfile?.let {
                            "${it.name} (${it.nozzleTempMin}-${it.nozzleTempMax}\u00B0C)"
                        } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.filament)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = profileExpanded,
                        onDismissRequest = { profileExpanded = false },
                    ) {
                        for (profile in filtered) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.filament_profile_label, profile.name, profile.nozzleTempMin, profile.nozzleTempMax))
                                },
                                onClick = {
                                    selectedProfile = profile
                                    profileExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))

                // Color presets
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilamentPresetColors.forEach { (_, color) ->
                        val argb = color.toArgb()
                        val isSelected = selectedColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    selectedColorArgb = argb
                                    hexInput = String.format("#%06X", 0xFFFFFF and argb)
                                },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input
                        val hex = input.trim().removePrefix("#")
                        if (hex.length == 6) {
                            try {
                                selectedColorArgb = (0xFF000000 or hex.toLong(16)).toInt()
                            } catch (_: NumberFormatException) {}
                        }
                    },
                    label = { Text(stringResource(R.string.hex_color)) },
                    placeholder = { Text(stringResource(R.string.hint_color_deep_orange)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedProfile?.let { profile ->
                        val r = (selectedColorArgb shr 16) and 0xFF
                        val g = (selectedColorArgb shr 8) and 0xFF
                        val b = selectedColorArgb and 0xFF
                        val colorHex = String.format("%02X%02X%02XFF", r, g, b)
                        onConfirm(profile, colorHex)
                    }
                },
                enabled = selectedProfile != null,
            ) {
                Text(stringResource(R.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun fanSpeedPercent(value: Int): Int {
    // Fan speeds are 0-255 PWM values, convert to 0-100% matching BambuStudio's display
    // BambuStudio rounds to nearest 10%: round(value / 25.5) * 10
    return (kotlin.math.round(value / 25.5f) * 10).toInt()
}

@Composable
private fun RepeatingIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        // Fire once immediately on press
        currentOnClick()
        // Start repeating after hold delay
        delay(400)
        var interval = 150L
        while (pressed) {
            currentOnClick()
            delay(interval)
            interval = (interval - 10).coerceAtLeast(30)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemperatureDialog(
    title: String,
    presetKey: String,
    currentTemp: Int,
    maxTemp: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val horizontalPadding = (LocalConfiguration.current.screenWidthDp * 0.12f).dp
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("temp_presets", android.content.Context.MODE_PRIVATE) }
    var targetTemp by remember { mutableIntStateOf(currentTemp) }

    fun loadPresets(): List<Int> {
        val csv = prefs.getString(presetKey, "") ?: ""
        return csv.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    val presets = remember { loadPresets().toMutableStateList() }

    fun savePresets() {
        prefs.edit().putString(presetKey, presets.joinToString(",")).apply()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RepeatingIconButton(onClick = { targetTemp = (targetTemp - 1).coerceAtLeast(0) }) {
                        Text("−", style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(
                        text = stringResource(R.string.target_temp_celsius, targetTemp),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                    RepeatingIconButton(onClick = { targetTemp = (targetTemp + 1).coerceAtMost(maxTemp) }) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                if (presets.isNotEmpty() || presets.size < 5) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = targetTemp == preset,
                                onClick = { targetTemp = preset },
                                label = { Text(stringResource(R.string.temperature_celsius, preset)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_remove_preset),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                presets.remove(preset)
                                                savePresets()
                                            },
                                    )
                                },
                            )
                        }
                        if (presets.size < 5 && targetTemp !in presets) {
                            AssistChip(
                                onClick = {
                                    presets.add(targetTemp)
                                    presets.sort()
                                    savePresets()
                                },
                                label = { Text(stringResource(R.string.save_temperature_celsius, targetTemp)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.cd_save_preset),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(targetTemp) }) {
                Text(stringResource(R.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FanSpeedDialog(
    title: String,
    presetKey: String,
    currentSpeedPwm: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val horizontalPadding = (LocalConfiguration.current.screenWidthDp * 0.12f).dp
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("temp_presets", android.content.Context.MODE_PRIVATE) }
    val initialPercent = kotlin.math.round(currentSpeedPwm * 100f / 255f).toInt()
    var targetSpeed by remember { mutableIntStateOf(initialPercent) }

    fun loadPresets(): List<Int> {
        val csv = prefs.getString(presetKey, "") ?: ""
        return csv.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    val presets = remember { loadPresets().toMutableStateList() }

    fun savePresets() {
        prefs.edit().putString(presetKey, presets.joinToString(",")).apply()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RepeatingIconButton(onClick = { targetSpeed = (targetSpeed - 10).coerceAtLeast(0) }) {
                        Text("−", style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(
                        text = stringResource(R.string.percent, targetSpeed),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                    RepeatingIconButton(onClick = { targetSpeed = (targetSpeed + 10).coerceAtMost(100) }) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                if (presets.isNotEmpty() || presets.size < 5) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = targetSpeed == preset,
                                onClick = { targetSpeed = preset },
                                label = { Text(stringResource(R.string.percent, preset)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_remove_preset),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                presets.remove(preset)
                                                savePresets()
                                            },
                                    )
                                },
                            )
                        }
                        if (presets.size < 5 && targetSpeed !in presets) {
                            AssistChip(
                                onClick = {
                                    presets.add(targetSpeed)
                                    presets.sort()
                                    savePresets()
                                },
                                label = { Text(stringResource(R.string.save_percent, targetSpeed)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.cd_save_preset),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(targetSpeed) }) {
                Text(stringResource(R.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SpeedLevelDialog(
    currentLevel: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedLevel by remember { mutableIntStateOf(currentLevel) }

    val speedOptions = listOf(
        1 to "Silent (50%)",
        2 to "Normal (100%)",
        3 to "Sport (124%)",
        4 to "Ludicrous (166%)",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.print_speed)) },
        text = {
            Column {
                for ((level, label) in speedOptions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLevel = level }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedLevel == level,
                            onClick = { selectedLevel = level },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedLevel) }) {
                Text(stringResource(R.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private data class FilamentEditTarget(
    val amsId: Int,
    val trayId: Int,
    val type: String,
    val color: String,
    val trayInfoIdx: String,
)

private fun parseHexColor(hex: String): Color {
    if (hex.length < 6) return Color.Gray
    return try {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val a = if (hex.length >= 8) hex.substring(6, 8).toInt(16) else 255
        Color(android.graphics.Color.argb(a, r, g, b))
    } catch (_: Exception) {
        Color.Gray
    }
}

private fun contrastTextColor(bg: Color): Color {
    val luminance = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (luminance > 0.55f) Color.Black else Color.White
}
