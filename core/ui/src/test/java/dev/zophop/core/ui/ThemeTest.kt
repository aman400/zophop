package dev.zophop.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun themeModeFromId_handlesCaseAndUnknownFallback() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromId(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromId("unknown"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromId("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromId("DARK"))
    }

    @Test
    fun themePaletteFromId_handlesCaseAndUnknownFallback() {
        assertEquals(AppThemePalette.EMERALD_AMBER, AppThemePalette.fromId(null))
        assertEquals(AppThemePalette.EMERALD_AMBER, AppThemePalette.fromId("nonexistent"))
        assertEquals(AppThemePalette.OCEAN_CYAN, AppThemePalette.fromId("ocean_cyan"))
        assertEquals(AppThemePalette.INDIGO_VIOLET, AppThemePalette.fromId("INDIGO_VIOLET"))
        assertEquals(AppThemePalette.CRIMSON_CORAL, AppThemePalette.fromId("CRIMSON_CORAL"))
        assertEquals(AppThemePalette.FOREST_CITRUS, AppThemePalette.fromId("FOREST_CITRUS"))
        assertEquals(AppThemePalette.SLATE_GRAPHITE, AppThemePalette.fromId("SLATE_GRAPHITE"))
        assertEquals(AppThemePalette.DYNAMIC, AppThemePalette.fromId("DYNAMIC"))
    }

    @Test
    fun availablePalettes_filtersDynamicBasedOnApiSupport() {
        val withoutDynamic = AppThemePalette.availablePalettes(supportsDynamicColor = false)
        assertFalse(withoutDynamic.contains(AppThemePalette.DYNAMIC))
        assertEquals(6, withoutDynamic.size)

        val withDynamic = AppThemePalette.availablePalettes(supportsDynamicColor = true)
        assertTrue(withDynamic.contains(AppThemePalette.DYNAMIC))
        assertEquals(7, withDynamic.size)
    }

    @Test
    fun allCuratedPalettes_haveDistinctLightAndDarkPrimaryColors() {
        val curated = AppThemePalette.availablePalettes(supportsDynamicColor = false)
        val lightPrimaries = curated.map { it.lightColorScheme.primary }.toSet()
        val darkPrimaries = curated.map { it.darkColorScheme.primary }.toSet()
        assertEquals(curated.size, lightPrimaries.size)
        assertEquals(curated.size, darkPrimaries.size)

        curated.forEach { palette ->
            assertNotEquals(
                "Expected distinct light/dark background for ${palette.name}",
                palette.lightColorScheme.background,
                palette.darkColorScheme.background,
            )
        }
    }
}
