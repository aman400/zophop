package dev.zophop.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Status Colors (Light Theme)
val StatusSuccessBgLight = Color(0xFFD1FAE5)
val StatusSuccessFgLight = Color(0xFF065F46)

val StatusWarningBgLight = Color(0xFFFEF3C7)
val StatusWarningFgLight = Color(0xFF92400E)

val StatusExpiredBgLight = Color(0xFFFFEDD5)
val StatusExpiredFgLight = Color(0xFF9A3412)

val StatusErrorBgLight = Color(0xFFFEE2E2)
val StatusErrorFgLight = Color(0xFF991B1B)

// Status Colors (Dark Theme)
val StatusSuccessBgDark = Color(0xFF064E3B)
val StatusSuccessFgDark = Color(0xFF34D399)

val StatusWarningBgDark = Color(0xFF78350F)
val StatusWarningFgDark = Color(0xFFFBBF24)

val StatusExpiredBgDark = Color(0xFF7C2D12)
val StatusExpiredFgDark = Color(0xFFFB923C)

val StatusErrorBgDark = Color(0xFF7F1D1D)
val StatusErrorFgDark = Color(0xFFFCA5A5)

// Workflow & Global Brand Color Palette (Emerald & Amber)
val EditorPrimaryEmerald = Color(0xFF059669)
val EditorPrimaryEmeraldDark = Color(0xFF10B981)

val EditorSecondaryAmber = Color(0xFFD97706)
val EditorSecondaryAmberDark = Color(0xFFF59E0B)

val EditorDarkColorScheme =
    darkColorScheme(
        primary = EditorPrimaryEmeraldDark,
        onPrimary = Color(0xFF022C22),
        primaryContainer = Color(0xFF064E3B),
        onPrimaryContainer = Color(0xFFD1FAE5),
        secondary = EditorSecondaryAmberDark,
        onSecondary = Color(0xFF451A03),
        secondaryContainer = Color(0xFF78350F),
        onSecondaryContainer = Color(0xFFFEF3C7),
        background = Color(0xFF090D16),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1F2937),
        onSurfaceVariant = Color(0xFF9CA3AF),
    )

val EditorLightColorScheme =
    lightColorScheme(
        primary = EditorPrimaryEmerald,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFECFDF5),
        onPrimaryContainer = Color(0xFF065F46),
        secondary = EditorSecondaryAmber,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFEF3C7),
        onSecondaryContainer = Color(0xFF92400E),
        background = Color(0xFFF0FDF4),
        onBackground = Color(0xFF064E3B),
        surface = Color.White,
        onSurface = Color(0xFF064E3B),
        surfaceVariant = Color(0xFFE6F4EA),
        onSurfaceVariant = Color(0xFF047857),
    )

private val DarkColorScheme = EditorDarkColorScheme
private val LightColorScheme = EditorLightColorScheme

@Composable
fun ZopHopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

object AppStatusColors {
    @Composable
    fun successColors(): Pair<Color, Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) StatusSuccessBgDark to StatusSuccessFgDark else StatusSuccessBgLight to StatusSuccessFgLight
    }

    @Composable
    fun warningColors(): Pair<Color, Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) StatusWarningBgDark to StatusWarningFgDark else StatusWarningBgLight to StatusWarningFgLight
    }

    @Composable
    fun expiredColors(): Pair<Color, Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) StatusExpiredBgDark to StatusExpiredFgDark else StatusExpiredBgLight to StatusExpiredFgLight
    }

    @Composable
    fun errorColors(): Pair<Color, Color> {
        val isDark = isSystemInDarkTheme()
        return if (isDark) StatusErrorBgDark to StatusErrorFgDark else StatusErrorBgLight to StatusErrorFgLight
    }
}
