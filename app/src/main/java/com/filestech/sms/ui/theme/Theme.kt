package com.filestech.sms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    // Two fixed schemes only: Light and one Dark (`#1B1B1B`). The dynamic-colours
    // toggle and the AMOLED true-black toggle still exist in Settings, but they
    // resolve onto these two schemes — dynamic colours / true black no longer
    // re-derive the palette from the wallpaper. The options are kept (and their
    // stored values untouched) so a later pass can refresh or remove them without
    // a settings migration. (The DarkTech theme mode was removed in feedback
    // round 4; a stored `DARK_TECH` value falls back to System at read time.)
    val useDark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val baseScheme: ColorScheme = if (useDark) darkScheme() else lightScheme()

    // A user-chosen accent still overrides primary; without one the app uses the
    // fixed Relay blue. The content colour is derived from the accent itself (see
    // [onBrandContainer]) instead of staying white: with the dark scheme's dark
    // `onPrimary`, a dark custom accent would otherwise pair dark-on-dark, and a
    // light accent with a white `onPrimary` would pair white-on-light.
    val scheme = appearance.customAccentArgb
        ?.let { argb ->
            val accent = Color(argb)
            baseScheme.copy(primary = accent, onPrimary = onBrandContainer(accent))
        }
        ?: baseScheme

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
