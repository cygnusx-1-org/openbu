package org.cygnusx1.openbu.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.AmsUnit
import org.cygnusx1.openbu.network.PrinterStatus

@Composable
fun DashboardScreen(
    frame: Bitmap?,
    fps: Float,
    isLightOn: Boolean?,
    isMqttConnected: Boolean,
    printerStatus: PrinterStatus,
    showMainStream: Boolean,
    rtspPlayer: ExoPlayer?,
    onToggleLight: (Boolean) -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenRtspFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Openbu",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Mini video preview
        if (showMainStream) {
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
                            contentDescription = "Camera preview",
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
                            text = "%.1f FPS".format(fps),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // External RTSP stream
        if (rtspPlayer != null) {
            RtspStreamCard(player = rtspPlayer, onClick = onOpenRtspFullscreen)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Chamber light control
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Chamber Light",
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

        Spacer(modifier = Modifier.height(8.dp))

        // Status
        PrintStatusCard(printerStatus = printerStatus)

        Spacer(modifier = Modifier.height(8.dp))

        // Nozzle & Bed temps side by side
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStatusCard(
                title = "Nozzle",
                iconRes = R.drawable.ic_nozzle,
                value = "%.1f / %.1f \u00B0C".format(printerStatus.nozzleTemper, printerStatus.nozzleTargetTemper),
                modifier = Modifier.weight(1f),
            )
            IconStatusCard(
                title = "Bed",
                iconRes = R.drawable.ic_bed,
                value = "%.1f / %.1f \u00B0C".format(printerStatus.bedTemper, printerStatus.bedTargetTemper),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Fan speeds
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStatusCard(
                title = "Part fan",
                iconRes = R.drawable.ic_part_fan,
                value = "${fanSpeedPercent(printerStatus.heatbreakFanSpeed)}%",
                modifier = Modifier.weight(1f),
            )
            IconStatusCard(
                title = "Aux fan",
                iconRes = R.drawable.ic_aux_fan,
                value = "${fanSpeedPercent(printerStatus.coolingFanSpeed)}%",
                modifier = Modifier.weight(1f),
            )
            IconStatusCard(
                title = "Chamber fan",
                iconRes = R.drawable.ic_chamber_fan,
                value = "${fanSpeedPercent(printerStatus.bigFan1Speed)}%",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // AMS cards
        for (amsUnit in printerStatus.amsUnits) {
            AmsCard(amsUnit)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PrintStatusCard(printerStatus: PrinterStatus) {
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

    val timeText = when {
        remainingMin > 0 && totalMin > 0 -> "${formatTime(remainingMin)} / ${formatTime(totalMin)}"
        remainingMin > 0 -> formatTime(remainingMin)
        else -> ""
    }

    val fileName = printerStatus.gcodeFile.substringAfterLast("/")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // Progress bar with percentage and layer info
            if (printerStatus.gcodeState == "RUNNING" || printerStatus.gcodeState == "PAUSE") {
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
                            text = "$percent%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = "Layer ${printerStatus.layerNum}/${printerStatus.totalLayerNum}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
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
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun AmsCard(amsUnit: AmsUnit) {
    val amsLabel = amsUnit.model.ifEmpty { "AMS" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "$amsLabel  ${amsUnit.temp}\u00B0C / Humidity ${amsUnit.humidity}%",
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(tray.trayColor)),
                            )
                            if (tray.trayType.isNotEmpty()) {
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
            }
        }
    }
}

@Composable
private fun RtspStreamCard(player: ExoPlayer, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
    ) {
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
    }
}

private fun fanSpeedPercent(raw: String): Int {
    val value = raw.toIntOrNull() ?: return 0
    // Bambu reports fan speed as 0-15, map to percentage
    return ((value / 15.0) * 100).toInt()
}

private fun parseHexColor(hex: String): Color {
    if (hex.length < 6) return Color.Gray
    return try {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val a = if (hex.length >= 8) hex.substring(6, 8).toInt(16) else 255
        Color(r, g, b, a)
    } catch (_: Exception) {
        Color.Gray
    }
}
