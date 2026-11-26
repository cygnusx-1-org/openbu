package org.cygnusx1.openbu.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

// Only show the spinner once a stall has lasted long enough to be noticeable.
private const val STALL_SPINNER_DELAY_MS = 500L

/**
 * True while player isn't delivering video: connecting/stalled (BUFFERING), or the stream ended
 * or failed and MainActivity's retry is about to rebuild the player. IDLE without an error is a
 * deliberate stop (app backgrounded), not a stall.
 */
private fun Player.isStalled(): Boolean = when (playbackState) {
    Player.STATE_BUFFERING, Player.STATE_ENDED -> true
    Player.STATE_IDLE -> playerError != null
    else -> false
}

/**
 * Centered spinner over an RTSP video view while the stream hangs or is reconnecting. Keyed on
 * [player], so it follows the replacement player when the stream is rebuilt.
 */
@Composable
fun BoxScope.RtspStallIndicator(player: Player) {
    var stalled by remember(player) { mutableStateOf(player.isStalled()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                stalled = player.isStalled()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var showSpinner by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player, stalled) {
        if (stalled) {
            delay(STALL_SPINNER_DELAY_MS)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }

    if (showSpinner) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(40.dp),
            color = Color.White,
        )
    }
}
