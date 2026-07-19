package org.cygnusx1.openbu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.cygnusx1.openbu.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

@Composable
fun RtspStreamScreen(player: ExoPlayer?, isRelayed: Boolean = false) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val offsetY = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale.floatValue = 1f
                    offsetX.floatValue = 0f
                    offsetY.floatValue = 0f
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale.floatValue * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    scale.floatValue = newScale
                    if (newScale == 1f) {
                        offsetX.floatValue = 0f
                        offsetY.floatValue = 0f
                    } else {
                        val maxX = (size.width * (newScale - 1)) / 2
                        val maxY = (size.height * (newScale - 1)) / 2
                        offsetX.floatValue = (offsetX.floatValue + pan.x).coerceIn(-maxX, maxX)
                        offsetY.floatValue = (offsetY.floatValue + pan.y).coerceIn(-maxY, maxY)
                    }
                }
            },
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale.floatValue
                        scaleY = scale.floatValue
                        translationX = offsetX.floatValue
                        translationY = offsetY.floatValue
                    },
            )
        }

        if (isRelayed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.relayed),
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
