package org.cygnusx1.openbu.ui

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val TAG = "VlcRtsp"
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

@Composable
private fun rememberVlcPlayer(url: String): Pair<LibVLC, MediaPlayer>? {
    val context = LocalContext.current
    val result = remember(url) {
        if (url.isBlank()) return@remember null
        Log.d(TAG, "Creating VLC player for: $url")
        val vlc = LibVLC(context, arrayListOf(
            "--network-caching=300",
            "--rtsp-tcp",
            "--no-audio",
        ))
        val player = MediaPlayer(vlc)
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(false, true)
        player.media = media
        media.release()
        Pair(vlc, player)
    }
    DisposableEffect(url) {
        onDispose {
            result?.let { (vlc, player) ->
                Log.d(TAG, "Disposing VLC player")
                player.stop()
                player.detachViews()
                player.release()
                vlc.release()
            }
        }
    }
    return result
}

@Composable
fun VlcRtspStreamCard(url: String, onClick: () -> Unit) {
    val vlcPair = rememberVlcPlayer(url)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
    ) {
        if (vlcPair != null) {
            val (_, player) = vlcPair
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        player.attachViews(layout, null, false, false)
                        player.play()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            )
        }
    }
}

@Composable
fun VlcRtspStreamScreen(url: String) {
    val vlcPair = rememberVlcPlayer(url)
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
                        offsetX.floatValue = (offsetX.floatValue + pan.x).coerceIn(-maxX.toFloat(), maxX.toFloat())
                        offsetY.floatValue = (offsetY.floatValue + pan.y).coerceIn(-maxY.toFloat(), maxY.toFloat())
                    }
                }
            },
    ) {
        if (vlcPair != null) {
            val (_, player) = vlcPair
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        player.attachViews(layout, null, false, false)
                        player.play()
                    }
                },
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
    }
}
