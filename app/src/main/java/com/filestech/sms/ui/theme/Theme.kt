package com.filestech.sms.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.filestech.sms.domain.settings.Appearance
import com.filestech.sms.domain.settings.ThemeMode

@Composable
fun SmsTechTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    // (The DarkTech theme mode was removed in feedback round 4; a stored
    // `DARK_TECH` value falls back to System at read time.)
    val useDark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Round 19 — dynamic colours (Material You, Android 12+): when the
    // toggle is on, the scheme is derived from the wallpaper instead of
    // the two fixed palettes. Off by default; below Android 12 the toggle
    // does nothing and the fixed palettes apply. The fixed Relay blue
    // still shows where the app paints it directly (bubbles, primary
    // buttons) — only the scheme accents follow the wallpaper.
    val context = LocalContext.current
    val useDynamic = appearance.dynamicColors &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val baseScheme: ColorScheme = when {
        useDynamic && useDark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        useDark -> darkScheme()
        else -> lightScheme()
    }
    // Round 19 — AMOLED true black: in dark mode, every surface goes
    // pure black so the pixels switch off. Accents, text and content
    // colours are untouched. Applies on top of the dynamic scheme too.
    val amoledScheme = if (useDark && appearance.amoledTrueBlack) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
            surfaceDim = Color.Black,
        )
    } else {
        baseScheme
    }

    // A user-chosen accent still overrides primary; without one the app uses the
    // fixed Relay blue. The content colour is derived from the accent itself (see
    // [onBrandContainer]) instead of staying white: with the dark scheme's dark
    // `onPrimary`, a dark custom accent would otherwise pair dark-on-dark, and a
    // light accent with a white `onPrimary` would pair white-on-light.
    val scheme = appearance.customAccentArgb
        ?.let { argb ->
            val accent = Color(argb)
            amoledScheme.copy(primary = accent, onPrimary = onBrandContainer(accent))
        }
        ?: amoledScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            // `Window.statusBarColor` is deprecated on API 35+ (Android 15) — the new
            // edge-to-edge model paints the system bars transparently by default once
            // `enableEdgeToEdge()` is called on the Activity. Until we migrate the whole app
            // to edge-to-edge (separate chantier — needs Scaffold inset handling audit), we
            // keep the explicit transparent assignment so the bar stays correct on API < 35.
            // The `@Suppress` is scoped to this single line on purpose.
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    // La typographie de l'appli reste AppTypography ; le coefficient n'agit que dans les
    // périmètres [MessageTextSize] (messages, saisie, listes de conversations).
    CompositionLocalProvider(LocalMessageTextFactor provides appearance.textScale.factor()) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
