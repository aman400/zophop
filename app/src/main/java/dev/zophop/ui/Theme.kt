package dev.zophop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.zophop.core.ui.AppThemeMode
import dev.zophop.core.ui.AppThemePalette
import dev.zophop.core.ui.ZopHopTheme as CoreZopHopTheme

@Composable
fun ZopHopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePalette: AppThemePalette = AppThemePalette.EMERALD_AMBER,
    content: @Composable () -> Unit,
) {
    CoreZopHopTheme(
        darkTheme = darkTheme,
        themePalette = themePalette,
        content = content,
    )
}

@Composable
fun ZopHopTheme(
    themeMode: AppThemeMode,
    themePalette: AppThemePalette = AppThemePalette.EMERALD_AMBER,
    content: @Composable () -> Unit,
) {
    CoreZopHopTheme(
        themeMode = themeMode,
        themePalette = themePalette,
        content = content,
    )
}
