package org.cygnusx1.openbu.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.cygnusx1.openbu.R
import androidx.compose.ui.unit.sp
import org.cygnusx1.openbu.network.PrintableObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipObjectsScreen(
    objects: List<PrintableObject>,
    skippedObjectIds: List<Int>,
    isLoading: Boolean,
    loadingMessage: String,
    error: String?,
    gcodeState: String,
    layerNum: Int,
    plateImage: Bitmap?,
    onLoadObjects: () -> Unit,
    onSkipSelected: (List<Int>) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
) {
    val canSkipObjects = gcodeState == "RUNNING" && layerNum >= 2

    LaunchedEffect(Unit) {
        onLoadObjects()
    }

    var selectedIds by remember { mutableStateOf(setOf<Int>()) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val nonSkippedObjects = objects.filter { it.identifyId !in skippedObjectIds }
    val nonSkippedCount = nonSkippedObjects.size
    val maxSelectable = nonSkippedCount - 1
    val canSkip = selectedIds.isNotEmpty() && selectedIds.size <= maxSelectable

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.skip_objects)) },
            text = {
                Text(stringResource(R.string.skip_objects_confirm, selectedIds.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    onSkipSelected(selectedIds.toList())
                    selectedIds = emptySet()
                }) {
                    Text(stringResource(R.string.skip))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skip_objects)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when {
                !canSkipObjects -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Printer must be actively printing (layer 2+) to skip objects",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                onClearError()
                                onLoadObjects()
                            }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(loadingMessage)
                        }
                    }
                }
                objects.size <= 1 -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Only one object on plate — nothing to skip",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                else -> {
                    // Plate image with object markers
                    if (plateImage != null) {
                        PlateImageWithMarkers(
                            plateImage = plateImage,
                            objects = objects,
                            skippedObjectIds = skippedObjectIds,
                            selectedIds = selectedIds,
                            maxSelectable = maxSelectable,
                            onToggleSelection = { id ->
                                selectedIds = if (id in selectedIds) {
                                    selectedIds - id
                                } else {
                                    if (selectedIds.size < maxSelectable) selectedIds + id else selectedIds
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }

                    if (selectedIds.size >= maxSelectable && maxSelectable > 0) {
                        Text(
                            "Must leave at least 1 object not skipped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    // Object list with checkboxes
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                    ) {
                        items(objects, key = { it.identifyId }) { obj ->
                            val isSkipped = obj.identifyId in skippedObjectIds
                            val isSelected = obj.identifyId in selectedIds
                            val atLimit = selectedIds.size >= maxSelectable && !isSelected

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            selectedIds + obj.identifyId
                                        } else {
                                            selectedIds - obj.identifyId
                                        }
                                    },
                                    enabled = !isSkipped && !atLimit,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.object_label, obj.identifyId, obj.name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (isSkipped) TextDecoration.LineThrough else null,
                                        color = if (isSkipped) {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (isSkipped) {
                                        Text(
                                            text = stringResource(R.string.already_skipped),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showConfirmDialog = true },
                        enabled = canSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    ) {
                        Text(stringResource(R.string.skip_selected, selectedIds.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlateImageWithMarkers(
    plateImage: Bitmap,
    objects: List<PrintableObject>,
    skippedObjectIds: List<Int>,
    selectedIds: Set<Int>,
    maxSelectable: Int,
    onToggleSelection: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    val markerSizeDp = 36.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A2E))
            .aspectRatio(1f)
            .onSizeChanged { imageSize = it },
    ) {
        Image(
            bitmap = plateImage.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_build_plate),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        // Overlay markers at each object's image position
        if (imageSize.width > 0 && imageSize.height > 0) {
            for (obj in objects) {
                val imgX = obj.imgX ?: continue
                val imgY = obj.imgY ?: continue

                val isSkipped = obj.identifyId in skippedObjectIds
                val isSelected = obj.identifyId in selectedIds
                val atLimit = selectedIds.size >= maxSelectable && !isSelected

                val pxX = imgX * imageSize.width
                val pxY = imgY * imageSize.height
                val offsetX = with(density) { pxX.toDp() - markerSizeDp / 2 }
                val offsetY = with(density) { pxY.toDp() - markerSizeDp / 2 }

                val bgColor = when {
                    isSkipped -> Color.Gray.copy(alpha = 0.6f)
                    isSelected -> Color(0xFFEF5350) // red
                    else -> Color(0xFF4CAF50) // green
                }
                val borderColor = when {
                    isSelected -> Color.White
                    else -> Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .size(markerSizeDp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(2.dp, borderColor, CircleShape)
                        .then(
                            if (!isSkipped && !atLimit) {
                                Modifier.clickable { onToggleSelection(obj.identifyId) }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = obj.identifyId.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
