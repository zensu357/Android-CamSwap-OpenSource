package com.example.camswap.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme

import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    onPrimary = Purple40,
    primaryContainer = PurpleContainerDark,
    onPrimaryContainer = PurpleContainerLight,
    
    secondary = PurpleGrey80,
    onSecondary = PurpleGrey40,
    secondaryContainer = PurpleGreyContainerDark,
    onSecondaryContainer = PurpleGreyContainerLight,
    
    tertiary = Pink80,
    onTertiary = Pink40,
    tertiaryContainer = PinkContainerDark,
    onTertiaryContainer = PinkContainerLight,
    
    error = Error80,
    onError = Error40,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorContainerLight,
    
    background = Neutral10,
    onBackground = Neutral99,
    surface = Neutral10,
    onSurface = Neutral99,
    surfaceVariant = Neutral20,
    onSurfaceVariant = PurpleGrey80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    onPrimary = Color.White,
    primaryContainer = PurpleContainerLight,
    onPrimaryContainer = PurpleContainerDark,
    
    secondary = PurpleGrey40,
    onSecondary = Color.White,
    secondaryContainer = PurpleGreyContainerLight,
    onSecondaryContainer = PurpleGreyContainerDark,
    
    tertiary = Pink40,
    onTertiary = Color.White,
    tertiaryContainer = PinkContainerLight,
    onTertiaryContainer = PinkContainerDark,
    
    error = Error40,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorContainerDark,
    
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral90,
    onSurfaceVariant = PurpleGrey40
)

@Composable
fun CamSwapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
