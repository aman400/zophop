@file:Suppress("MagicNumber")

package dev.zophop.core.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

private val OceanLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF0284C7),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0F2FE),
        onPrimaryContainer = Color(0xFF0C4A6E),
        secondary = Color(0xFF0D9488),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCCFBF1),
        onSecondaryContainer = Color(0xFF115E59),
        background = Color(0xFFF0F9FF),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF334155),
    )

private val OceanDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF082F49),
        primaryContainer = Color(0xFF075985),
        onPrimaryContainer = Color(0xFFE0F2FE),
        secondary = Color(0xFF2DD4BF),
        onSecondary = Color(0xFF042F2E),
        secondaryContainer = Color(0xFF115E59),
        onSecondaryContainer = Color(0xFFCCFBF1),
        background = Color(0xFF08101A),
        onBackground = Color(0xFFF1F5F9),
        surface = Color(0xFF0F172A),
        onSurface = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFF94A3B8),
    )

private val IndigoLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF4F46E5),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E7FF),
        onPrimaryContainer = Color(0xFF312E81),
        secondary = Color(0xFF9333EA),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF3E8FF),
        onSecondaryContainer = Color(0xFF581C87),
        background = Color(0xFFF5F3FF),
        onBackground = Color(0xFF1E1B4B),
        surface = Color.White,
        onSurface = Color(0xFF1E1B4B),
        surfaceVariant = Color(0xFFE5E7EB),
        onSurfaceVariant = Color(0xFF4338CA),
    )

private val IndigoDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF818CF8),
        onPrimary = Color(0xFF1E1B4B),
        primaryContainer = Color(0xFF3730A3),
        onPrimaryContainer = Color(0xFFE0E7FF),
        secondary = Color(0xFFC084FC),
        onSecondary = Color(0xFF3B0764),
        secondaryContainer = Color(0xFF6B21A8),
        onSecondaryContainer = Color(0xFFF3E8FF),
        background = Color(0xFF0B0B17),
        onBackground = Color(0xFFF5F3FF),
        surface = Color(0xFF131426),
        onSurface = Color(0xFFF5F3FF),
        surfaceVariant = Color(0xFF1E1F36),
        onSurfaceVariant = Color(0xFFA5B4FC),
    )

private val CrimsonLightColorScheme =
    lightColorScheme(
        primary = Color(0xFFDC2626),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFEE2E2),
        onPrimaryContainer = Color(0xFF7F1D1D),
        secondary = Color(0xFFEA580C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFEDD5),
        onSecondaryContainer = Color(0xFF7C2D12),
        background = Color(0xFFFEF2F2),
        onBackground = Color(0xFF450A0A),
        surface = Color.White,
        onSurface = Color(0xFF2B1212),
        surfaceVariant = Color(0xFFF3E8E8),
        onSurfaceVariant = Color(0xFF7F1D1D),
    )

private val CrimsonDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFF87171),
        onPrimary = Color(0xFF450A0A),
        primaryContainer = Color(0xFF991B1B),
        onPrimaryContainer = Color(0xFFFEE2E2),
        secondary = Color(0xFFFB923C),
        onSecondary = Color(0xFF431407),
        secondaryContainer = Color(0xFF9A3412),
        onSecondaryContainer = Color(0xFFFFEDD5),
        background = Color(0xFF140B0D),
        onBackground = Color(0xFFFEF2F2),
        surface = Color(0xFF1F1215),
        onSurface = Color(0xFFFEF2F2),
        surfaceVariant = Color(0xFF2D1A1E),
        onSurfaceVariant = Color(0xFFFCA5A5),
    )

private val ForestCitrusLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF0F766E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCCFBF1),
        onPrimaryContainer = Color(0xFF134E4A),
        secondary = Color(0xFF65A30D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFECFCCB),
        onSecondaryContainer = Color(0xFF365314),
        background = Color(0xFFF0FDFA),
        onBackground = Color(0xFF134E4A),
        surface = Color.White,
        onSurface = Color(0xFF0F2E2B),
        surfaceVariant = Color(0xFFDDF3EE),
        onSurfaceVariant = Color(0xFF115E59),
    )

private val ForestCitrusDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF2DD4BF),
        onPrimary = Color(0xFF042F2E),
        primaryContainer = Color(0xFF115E59),
        onPrimaryContainer = Color(0xFFCCFBF1),
        secondary = Color(0xFFA3E635),
        onSecondary = Color(0xFF1A2E05),
        secondaryContainer = Color(0xFF3F6212),
        onSecondaryContainer = Color(0xFFECFCCB),
        background = Color(0xFF071211),
        onBackground = Color(0xFFF0FDFA),
        surface = Color(0xFF0E1D1B),
        onSurface = Color(0xFFF0FDFA),
        surfaceVariant = Color(0xFF172B29),
        onSurfaceVariant = Color(0xFF99F6E4),
    )

private val SlateLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF334155),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE2E8F0),
        onPrimaryContainer = Color(0xFF0F172A),
        secondary = Color(0xFF64748B),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF1F5F9),
        onSecondaryContainer = Color(0xFF334155),
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF475569),
    )

private val SlateDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFCBD5E1),
        onPrimary = Color(0xFF0F172A),
        primaryContainer = Color(0xFF334155),
        onPrimaryContainer = Color(0xFFF8FAFC),
        secondary = Color(0xFF94A3B8),
        onSecondary = Color(0xFF0F172A),
        secondaryContainer = Color(0xFF1E293B),
        onSecondaryContainer = Color(0xFFE2E8F0),
        background = Color(0xFF0A0D12),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF11161F),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1E2532),
        onSurfaceVariant = Color(0xFF94A3B8),
    )

enum class AppThemeMode(
    val id: String,
    val label: String,
) {
    SYSTEM("SYSTEM", "System"),
    LIGHT("LIGHT", "Light"),
    DARK("DARK", "Dark"),
    ;

    companion object {
        fun fromId(id: String?): AppThemeMode = entries.find { it.id.equals(id, ignoreCase = true) } ?: SYSTEM
    }
}

enum class AppThemePalette(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val previewPrimary: Color,
    val previewSecondary: Color,
    val previewSurface: Color,
    val lightColorScheme: ColorScheme,
    val darkColorScheme: ColorScheme,
) {
    EMERALD_AMBER(
        id = "EMERALD_AMBER",
        displayName = "Emerald & Amber",
        subtitle = "ZopHop classic green & gold",
        previewPrimary = Color(0xFF059669),
        previewSecondary = Color(0xFFD97706),
        previewSurface = Color(0xFFECFDF5),
        lightColorScheme = EditorLightColorScheme,
        darkColorScheme = EditorDarkColorScheme,
    ),
    OCEAN_CYAN(
        id = "OCEAN_CYAN",
        displayName = "Ocean & Teal",
        subtitle = "Crisp sky blue & coastal teal",
        previewPrimary = Color(0xFF0284C7),
        previewSecondary = Color(0xFF0D9488),
        previewSurface = Color(0xFFE0F2FE),
        lightColorScheme = OceanLightColorScheme,
        darkColorScheme = OceanDarkColorScheme,
    ),
    INDIGO_VIOLET(
        id = "INDIGO_VIOLET",
        displayName = "Indigo & Violet",
        subtitle = "Electric indigo & soft plum",
        previewPrimary = Color(0xFF4F46E5),
        previewSecondary = Color(0xFF9333EA),
        previewSurface = Color(0xFFE0E7FF),
        lightColorScheme = IndigoLightColorScheme,
        darkColorScheme = IndigoDarkColorScheme,
    ),
    CRIMSON_CORAL(
        id = "CRIMSON_CORAL",
        displayName = "Crimson & Coral",
        subtitle = "Bold ruby & warm sunset",
        previewPrimary = Color(0xFFDC2626),
        previewSecondary = Color(0xFFEA580C),
        previewSurface = Color(0xFFFEE2E2),
        lightColorScheme = CrimsonLightColorScheme,
        darkColorScheme = CrimsonDarkColorScheme,
    ),
    FOREST_CITRUS(
        id = "FOREST_CITRUS",
        displayName = "Forest & Citrus",
        subtitle = "Deep pine teal & fresh lime",
        previewPrimary = Color(0xFF0F766E),
        previewSecondary = Color(0xFF65A30D),
        previewSurface = Color(0xFFCCFBF1),
        lightColorScheme = ForestCitrusLightColorScheme,
        darkColorScheme = ForestCitrusDarkColorScheme,
    ),
    SLATE_GRAPHITE(
        id = "SLATE_GRAPHITE",
        displayName = "Slate & Steel",
        subtitle = "Minimal monochrome charcoal",
        previewPrimary = Color(0xFF334155),
        previewSecondary = Color(0xFF64748B),
        previewSurface = Color(0xFFE2E8F0),
        lightColorScheme = SlateLightColorScheme,
        darkColorScheme = SlateDarkColorScheme,
    ),
    DYNAMIC(
        id = "DYNAMIC",
        displayName = "Material You",
        subtitle = "Matches device wallpaper",
        previewPrimary = Color(0xFF6750A4),
        previewSecondary = Color(0xFF006A6A),
        previewSurface = Color(0xFFEADDFF),
        lightColorScheme = EditorLightColorScheme,
        darkColorScheme = EditorDarkColorScheme,
    ),
    ;

    companion object {
        fun fromId(id: String?): AppThemePalette =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: EMERALD_AMBER

        fun availablePalettes(supportsDynamicColor: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S): List<AppThemePalette> =
            if (supportsDynamicColor) {
                entries.toList()
            } else {
                entries.filterNot { it == DYNAMIC }
            }
    }
}

val LocalAppIsDark = compositionLocalOf<Boolean?> { null }

@Composable
fun resolveDarkTheme(themeMode: AppThemeMode): Boolean =
    when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

fun resolveColorScheme(
    context: Context,
    palette: AppThemePalette,
    darkTheme: Boolean,
): ColorScheme {
    if (palette == AppThemePalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (darkTheme) palette.darkColorScheme else palette.lightColorScheme
}

@Composable
fun ZopHopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePalette: AppThemePalette = AppThemePalette.EMERALD_AMBER,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = resolveColorScheme(context = context, palette = themePalette, darkTheme = darkTheme)

    CompositionLocalProvider(LocalAppIsDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

@Composable
fun ZopHopTheme(
    themeMode: AppThemeMode,
    themePalette: AppThemePalette = AppThemePalette.EMERALD_AMBER,
    content: @Composable () -> Unit,
) {
    ZopHopTheme(
        darkTheme = resolveDarkTheme(themeMode),
        themePalette = themePalette,
        content = content,
    )
}

object AppStatusColors {
    @Composable
    fun successColors(): Pair<Color, Color> {
        val isDark = LocalAppIsDark.current ?: isSystemInDarkTheme()
        return if (isDark) StatusSuccessBgDark to StatusSuccessFgDark else StatusSuccessBgLight to StatusSuccessFgLight
    }

    @Composable
    fun warningColors(): Pair<Color, Color> {
        val isDark = LocalAppIsDark.current ?: isSystemInDarkTheme()
        return if (isDark) StatusWarningBgDark to StatusWarningFgDark else StatusWarningBgLight to StatusWarningFgLight
    }

    @Composable
    fun expiredColors(): Pair<Color, Color> {
        val isDark = LocalAppIsDark.current ?: isSystemInDarkTheme()
        return if (isDark) StatusExpiredBgDark to StatusExpiredFgDark else StatusExpiredBgLight to StatusExpiredFgLight
    }

    @Composable
    fun errorColors(): Pair<Color, Color> {
        val isDark = LocalAppIsDark.current ?: isSystemInDarkTheme()
        return if (isDark) StatusErrorBgDark to StatusErrorFgDark else StatusErrorBgLight to StatusErrorFgLight
    }
}
