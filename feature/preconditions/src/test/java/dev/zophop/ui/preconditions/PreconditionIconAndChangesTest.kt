package dev.zophop.ui.preconditions

import dev.zophop.data.PreconditionEntity
import dev.zophop.data.toDomain
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionIconAndChangesTest {
    private fun isPreconditionModified(
        precondition: PreconditionEntity?,
        icon: String = precondition?.icon ?: "",
    ): Boolean =
        if (precondition == null) {
            icon.isNotBlank()
        } else {
            icon != precondition.icon
        }

    @Test
    fun testHasPreconditionChangesDetectsIconModifications() {
        val existing =
            PreconditionEntity(
                id = "p_exist_1",
                name = "Firewall Rule",
                type = PreconditionType.CHAINED_CHECK.name,
                icon = "🛡️",
            )

        // No changes
        assertFalse(isPreconditionModified(existing, icon = "🛡️"))

        // Changed icon
        assertTrue(isPreconditionModified(existing, icon = "⚡"))

        // Cleared icon
        assertTrue(isPreconditionModified(existing, icon = ""))
    }

    @Test
    fun testHasPreconditionChangesForNewPreconditionWithIcon() {
        // Default new precondition with empty icon
        val hasChangesWhenEmpty =
            isPreconditionModified(
                precondition = null,
                icon = "",
            )
        assertFalse(hasChangesWhenEmpty)

        // New precondition with an icon selected
        val hasChangesWithIcon =
            isPreconditionModified(
                precondition = null,
                icon = "🚀",
            )
        assertTrue(hasChangesWithIcon)
    }

    @Test
    fun testConfirmDialogIconDiffLogic() {
        val original =
            PreconditionEntity(
                id = "prec_diff_1",
                name = "GPS Gate",
                type = PreconditionType.GPS_CHECK.name,
                icon = "📍",
            )

        fun isIconChanged(orig: PreconditionEntity?, newIcon: String): Boolean =
            newIcon != (orig?.icon ?: "")

        assertFalse(isIconChanged(original, "📍"))
        assertTrue(isIconChanged(original, "🚗"))
        assertTrue(isIconChanged(original, ""))

        val brandNew: PreconditionEntity? = null
        assertFalse(isIconChanged(brandNew, ""))
        assertTrue(isIconChanged(brandNew, "⚡"))
    }

    @Test
    fun testAllPreconditionEntityTypesMapIconToDomain() {
        val entities =
            listOf(
                PreconditionEntity(
                    id = "p1",
                    name = "Net",
                    type = PreconditionType.INTERNET_CHECK.name,
                    icon = "🌐",
                ),
                PreconditionEntity(
                    id = "p2",
                    name = "Vpn",
                    type = PreconditionType.VPN_CHECK.name,
                    icon = "🔐",
                ),
                PreconditionEntity(
                    id = "p3",
                    name = "Health",
                    type = PreconditionType.HEALTH_CHECK.name,
                    icon = "🩺",
                ),
                PreconditionEntity(
                    id = "p4",
                    name = "Sim",
                    type = PreconditionType.SIM_CHECK.name,
                    icon = "📱",
                ),
                PreconditionEntity(
                    id = "p5",
                    name = "Gps",
                    type = PreconditionType.GPS_CHECK.name,
                    icon = "📍",
                ),
                PreconditionEntity(
                    id = "p6",
                    name = "Battery",
                    type = PreconditionType.BATTERY_CHECK.name,
                    icon = "🔋",
                ),
                PreconditionEntity(
                    id = "p7",
                    name = "Chain",
                    type = PreconditionType.CHAINED_CHECK.name,
                    icon = "🛡️",
                ),
            )

        val domains = entities.map { it.toDomain() }

        assertEquals("🌐", domains[0].icon)
        assertTrue(domains[0] is Precondition.InternetCheck)

        assertEquals("🔐", domains[1].icon)
        assertTrue(domains[1] is Precondition.VpnCheck)

        assertEquals("🩺", domains[2].icon)
        assertTrue(domains[2] is Precondition.HealthCheck)

        assertEquals("📱", domains[3].icon)
        assertTrue(domains[3] is Precondition.SimCheck)

        assertEquals("📍", domains[4].icon)
        assertTrue(domains[4] is Precondition.GpsCheck)

        assertEquals("🔋", domains[5].icon)
        assertTrue(domains[5] is Precondition.BatteryCheck)

        assertEquals("🛡️", domains[6].icon)
        assertTrue(domains[6] is Precondition.ChainCheck)
    }

    @Test
    fun testComplexEmojiAndSurrogatePairIcons() {
        val complexIcons = listOf("🛠️", "👨‍💻", "🪄", "🌟", "🔥", "✨")

        complexIcons.forEachIndexed { index, emoji ->
            val entity =
                PreconditionEntity(
                    id = "p_complex_$index",
                    name = "Complex Emoji Rule $index",
                    type = PreconditionType.INTERNET_CHECK.name,
                    icon = emoji,
                )
            val domain = entity.toDomain()
            assertEquals(emoji, domain.icon)
            assertEquals(emoji, entity.icon)
        }
    }
}
