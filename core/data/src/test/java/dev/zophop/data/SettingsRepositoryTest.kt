package dev.zophop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun testDeliveryNodeSerializationAndDeserialization() {
        val node1 =
            DeliveryNode(
                id = "node_1",
                name = "Ntfy Target",
                serverType = ServerType.NTFY,
                serverUrl = "https://ntfy.sh",
                topic = "my_topic",
                token = "tk_secret",
                isEnabled = true,
                requireInternet = true,
                requireVpn = false,
                requireHealthcheck = true,
                customHealthcheckUrl = "https://ntfy.sh/health",
                healthcheckMethod = "GET",
                healthcheckHeaders = "Header1: Val1\nHeader2: Val2",
                healthcheckExpectedStatus = 200,
            )

        val node2 =
            DeliveryNode(
                id = "node_2",
                name = "Gotify Server",
                serverType = ServerType.GOTIFY,
                serverUrl = "https://gotify.example.com",
                topic = "",
                token = "gotify_app_token",
                isEnabled = false,
                requireInternet = false,
                requireVpn = true,
                requireHealthcheck = false,
                customHealthcheckUrl = "",
                healthcheckMethod = "POST",
                healthcheckHeaders = "",
                healthcheckExpectedStatus = 204,
            )

        val serialized = EncryptedSettingsRepository.serializeNodes(listOf(node1, node2))
        val deserialized = EncryptedSettingsRepository.deserializeNodes(serialized)

        assertEquals(2, deserialized.size)

        val restored1 = deserialized[0]
        assertEquals("node_1", restored1.id)
        assertEquals("Ntfy Target", restored1.name)
        assertEquals(ServerType.NTFY, restored1.serverType)
        assertEquals("https://ntfy.sh", restored1.serverUrl)
        assertEquals("my_topic", restored1.topic)
        assertEquals("tk_secret", restored1.token)
        assertTrue(restored1.isEnabled)
        assertTrue(restored1.requireInternet)
        assertFalse(restored1.requireVpn)
        assertTrue(restored1.requireHealthcheck)
        assertEquals("https://ntfy.sh/health", restored1.customHealthcheckUrl)
        assertEquals("GET", restored1.healthcheckMethod)
        assertEquals("Header1: Val1\nHeader2: Val2", restored1.healthcheckHeaders)
        assertEquals(200, restored1.healthcheckExpectedStatus)

        val restored2 = deserialized[1]
        assertEquals("node_2", restored2.id)
        assertEquals("Gotify Server", restored2.name)
        assertFalse(restored2.isEnabled)
        assertTrue(restored2.requireVpn)
        assertEquals(204, restored2.healthcheckExpectedStatus)
    }

    @Test
    fun testEmptyAndMalformedNodeDeserialization() {
        val emptyNodes = EncryptedSettingsRepository.deserializeNodes("")
        assertTrue(emptyNodes.isEmpty())

        val blankNodes = EncryptedSettingsRepository.deserializeNodes("   ")
        assertTrue(blankNodes.isEmpty())

        val malformedNodes = EncryptedSettingsRepository.deserializeNodes("invalid:::format")
        assertTrue(malformedNodes.isEmpty())
    }

    @Test
    fun testAppSettingsAllowlistItemsParsing() {
        val settings =
            AppSettings(
                allowlist = "  +123456789 , +987654321 \n BankAlerts \n ",
            )

        val items = settings.allowlistItems
        assertEquals(3, items.size)
        assertEquals("+123456789", items[0])
        assertEquals("+987654321", items[1])
        assertEquals("BankAlerts", items[2])
    }

    @Test
    fun testAppSettingsActiveNodesFiltering() {
        val nodeEnabledValid = DeliveryNode(id = "n1", name = "Valid", serverUrl = "https://ntfy.sh", isEnabled = true)
        val nodeDisabledValid = DeliveryNode(id = "n2", name = "Disabled", serverUrl = "https://ntfy.sh", isEnabled = false)
        val nodeEnabledBlankUrl = DeliveryNode(id = "n3", name = "Blank", serverUrl = "  ", isEnabled = true)

        val settings =
            AppSettings(
                nodes = listOf(nodeEnabledValid, nodeDisabledValid, nodeEnabledBlankUrl),
            )

        val active = settings.activeNodes
        assertEquals(1, active.size)
        assertEquals("n1", active[0].id)
    }

    @Test
    fun testAppSettingsDefaultAndCustomThemeFields() {
        val defaultSettings = AppSettings()
        assertEquals("SYSTEM", defaultSettings.themeMode)
        assertEquals("EMERALD_AMBER", defaultSettings.themePalette)

        val customSettings =
            AppSettings(
                themeMode = "DARK",
                themePalette = "OCEAN_CYAN",
            )
        assertEquals("DARK", customSettings.themeMode)
        assertEquals("OCEAN_CYAN", customSettings.themePalette)
    }
}
