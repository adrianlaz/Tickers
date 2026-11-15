package com.tickers.app.ui.theme

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

val UpGreen = Color(0xFF1B873F)
val DownRed = Color(0xFFC62828)
val UpGreenDark = Color(0xFF4ADE80)
val DownRedDark = Color(0xFFF87171)

@Composable
fun changeColor(pct: Double?): Color {
    val dark = isSystemInDarkTheme()
    return if ((pct ?: 0.0) >= 0) {
        if (dark) UpGreenDark else UpGreen
    } else {
        if (dark) DownRedDark else DownRed
    }
}

@Composable
fun TickersTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
