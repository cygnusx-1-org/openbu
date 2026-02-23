package org.cygnusx1.openbu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme()
private val LightColorScheme = lightColorScheme()

@Composable
fun OpenbuTheme(
    overrideDeviceTheme: Boolean = false,
    customBackgroundColor: Int? = null,
    darkTheme: Boolean = isSystemInDarkTheme() xor overrideDeviceTheme,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }.let { scheme ->
        if (customBackgroundColor != null) {
            val bg = Color(customBackgroundColor)
            scheme.copy(background = bg, surface = bg)
        } else {
            scheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
