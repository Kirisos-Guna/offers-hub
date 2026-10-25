package com.offershub.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFFB5400B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF390C00),
    secondary = Color(0xFF77574A),
    secondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFF6A5E2F),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5C1900),
    primaryContainer = Color(0xFF842B00),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BEAE),
    secondaryContainer = Color(0xFF5D4034),
    tertiary = Color(0xFFD6C68D),
)

/** Store brand colours for badges and image placeholders. */
fun storeColor(store: String): Color = when (store) {
    "Amazon" -> Color(0xFFFF9900)
    "Flipkart" -> Color(0xFF2874F0)
    "Myntra" -> Color(0xFFFF3F6C)
    "Ajio" -> Color(0xFF2C4152)
    "Meesho" -> Color(0xFF9F2089)
    "Nykaa" -> Color(0xFFFC2779)
    "Tata CLiQ" -> Color(0xFF231F20)
    "Croma" -> Color(0xFF12DAA8)
    "JioMart" -> Color(0xFF0078AD)
    "Shopsy" -> Color(0xFF6A1B9A)
    else -> Color(0xFF607D8B)
}

@Composable
fun OffersHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
