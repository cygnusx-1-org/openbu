package org.cygnusx1.openbu.ui

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "VideoPlayerScreen"
private const val TARGET_PLAYBACK_SECONDS = 10f
private const val MIN_RATE = 0.01f
private const val MAX_RATE = 0.10f

/**
 * Estimate AVI duration from the main header (avih chunk).
 * Reads dwMicroSecPerFrame and dwTotalFrames to compute duration in seconds.
 * Returns 0 if the header can't be parsed.
 */
private fun estimateAviDuration(file: File): Float {
    try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(256.coerceAtMost(file.length().toInt()))
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // Find "avih" chunk — dwMicroSecPerFrame is at offset 0 within avih data,
            // dwTotalFrames is at offset 16
            val headerStr = String(header, Charsets.ISO_8859_1)
            val avihPos = headerStr.indexOf("avih")
            if (avihPos < 0 || avihPos + 8 + 24 > header.size) return 0f

            val dataStart = avihPos + 8 // skip "avih" + chunk size (4 bytes each)
            val microSecPerFrame = buf.getInt(dataStart).toLong() and 0xFFFFFFFFL
            val totalFrames = buf.getInt(dataStart + 16).toLong() and 0xFFFFFFFFL

            if (microSecPerFrame == 0L || totalFrames == 0L) return 0f

            val durationSec = (microSecPerFrame * totalFrames) / 1_000_000f
            Log.d(TAG, "AVI header: ${microSecPerFrame}us/frame, $totalFrames frames, ${durationSec}s")
            return durationSec
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse AVI header", e)
        return 0f
    }
}

private fun calcRate(durationSec: Float): Float {
    if (durationSec <= 0f) return MAX_RATE
    return (durationSec / TARGET_PLAYBACK_SECONDS).coerceIn(MIN_RATE, MAX_RATE)
}

@Composable
fun VideoPlayerScreen(
    videoFile: File,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current

    val (libVlc, mediaPlayer, rate) = remember(videoFile) {
        val durationSec = estimateAviDuration(videoFile)
        val rate = calcRate(durationSec)
        Log.d(TAG, "Calculated rate: $rate (duration: ${durationSec}s)")

        val vlc = LibVLC(context)
        val player = MediaPlayer(vlc)
        val media = Media(vlc, Uri.fromFile(videoFile))
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "Playing at rate: ${player.rate}")
                    if (player.rate != rate) {
                        player.rate = rate
                        Log.d(TAG, "Corrected rate to $rate")
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG, "Playback completed")
                    onFinished()
                }
            }
        }
        Triple(vlc, player, rate)
    }

    DisposableEffect(videoFile) {
        onDispose {
            Log.d(TAG, "Disposing VLC player")
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVlc.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    mediaPlayer.attachViews(layout, null, false, false)
                    mediaPlayer.rate = rate
                    mediaPlayer.play()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
