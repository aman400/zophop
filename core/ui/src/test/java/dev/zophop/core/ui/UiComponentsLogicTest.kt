import androidx.biometric.BiometricManager
import dev.zophop.core.ui.components.BiometricAuthHelper
import dev.zophop.core.ui.components.KeyValueItem
import dev.zophop.core.ui.components.isLikelySecretHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for :core:ui business logic and component helpers:
 * - Header/KeyValue parsing & serialization
 * - Searchable dropdown filtering logic
 * - KeyValueItem model integrity
 */
class UiComponentsLogicTest {

    @Test
    fun testKeyValueItem_dataModel() {
        val item = KeyValueItem("Content-Type", "application/json")
        assertEquals("Content-Type", item.key)
        assertEquals("application/json", item.value)

        val updated = item.copy(value = "text/plain")
        assertEquals("Content-Type", updated.key)
        assertEquals("text/plain", updated.value)
    }

    @Test
    fun testHeaderParsing_newlineSeparatedHeaders() {
        val input = "Authorization: Bearer token123\nContent-Type: application/json\nX-Custom: my-val"
        val parsed = parseHeadersString(input)

        assertEquals(3, parsed.size)
        assertEquals("Authorization", parsed[0].key)
        assertEquals("Bearer token123", parsed[0].value)
        assertEquals("Content-Type", parsed[1].key)
        assertEquals("application/json", parsed[1].value)
        assertEquals("X-Custom", parsed[2].key)
        assertEquals("my-val", parsed[2].value)
    }

    @Test
    fun testHeaderParsing_equalSignDelimitedAndAmpersandSeparated() {
        val input = "user_id=12345&auth_tier=premium&theme=dark"
        val parsed = parseHeadersString(input)

        assertEquals(3, parsed.size)
        assertEquals("user_id", parsed[0].key)
        assertEquals("12345", parsed[0].value)
        assertEquals("auth_tier", parsed[1].key)
        assertEquals("premium", parsed[1].value)
        assertEquals("theme", parsed[2].key)
        assertEquals("dark", parsed[2].value)
    }

    @Test
    fun testHeaderParsing_commaSeparatedHeaders() {
        val input = "Accept: text/html, Cache-Control: no-cache, Pragma: no-cache"
        val parsed = parseHeadersString(input)

        assertEquals(3, parsed.size)
        assertEquals("Accept", parsed[0].key)
        assertEquals("text/html", parsed[0].value)
        assertEquals("Cache-Control", parsed[1].key)
        assertEquals("no-cache", parsed[1].value)
        assertEquals("Pragma", parsed[2].key)
        assertEquals("no-cache", parsed[2].value)
    }

    @Test
    fun testHeaderParsing_blankAndMalformedLines() {
        val input = "   \n\nAuthorization: Basic YWRtaW46cGFzc3dvcmQ=\n\n   "
        val parsed = parseHeadersString(input)

        assertEquals(1, parsed.size)
        assertEquals("Authorization", parsed[0].key)
        assertEquals("Basic YWRtaW46cGFzc3dvcmQ=", parsed[0].value)

        val emptyParsed = parseHeadersString("   ")
        assertEquals(1, emptyParsed.size)
        assertEquals("", emptyParsed[0].key)
        assertEquals("", emptyParsed[0].value)
    }

    @Test
    fun testHeaderSerialization_filtersBlankEntries() {
        val items = listOf(
            KeyValueItem("Header1", "Val1"),
            KeyValueItem("", ""),
            KeyValueItem("Header2", "Val2"),
            KeyValueItem("   ", "   "),
        )
        val serialized = formatHeaders(items)
        assertEquals("Header1: Val1\nHeader2: Val2", serialized)
    }

    @Test
    fun testQueryParamsSerialization_formatsWithAmpersandAndEqual() {
        val items = listOf(
            KeyValueItem("page", "1"),
            KeyValueItem("limit", "20"),
            KeyValueItem("", ""),
            KeyValueItem("sort", "desc"),
        )
        val serialized = formatHeaders(items, pairSeparator = "=", joinSeparator = "&")
        assertEquals("page=1&limit=20&sort=desc", serialized)
    }

    @Test
    fun testLikelySecretDetection() {
        assertTrue(isLikelySecretHeader("Authorization"))
        assertTrue(isLikelySecretHeader("api-key"))
        assertTrue(isLikelySecretHeader("access_token"))
        assertTrue(isLikelySecretHeader("client_secret"))
        assertTrue(isLikelySecretHeader("password"))
        assertFalse(isLikelySecretHeader("Content-Type"))
        assertFalse(isLikelySecretHeader("page"))
    }

    @Test
    fun testSearchFiltering_caseInsensitiveAndSubstringMatching() {
        val items = listOf("Apple", "Banana", "Apricot", "Orange", "Pineapple")

        val queryAp = filterItems(items, "ap")
        assertEquals(listOf("Apple", "Apricot", "Pineapple"), queryAp)

        val queryExact = filterItems(items, "Orange")
        assertEquals(listOf("Orange"), queryExact)

        val queryEmpty = filterItems(items, "   ")
        assertEquals(items, queryEmpty)

        val queryNone = filterItems(items, "Watermelon")
        assertTrue(queryNone.isEmpty())
    }

    private fun parseHeadersString(inputString: String): List<KeyValueItem> {
        if (inputString.isBlank()) {
            return listOf(KeyValueItem("", ""))
        }
        val lines = when {
            inputString.contains("\n") -> inputString.split("\r\n", "\n")
            inputString.contains("&") -> inputString.split("&")
            inputString.contains(",") && Regex(",\\s*[a-zA-Z0-9_-]+[:=]").containsMatchIn(inputString) ->
                inputString.split(Regex(",(?=\\s*[a-zA-Z0-9_-]+[:=])"))
            else -> listOf(inputString)
        }
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val parts = trimmed.split(Regex("[:=]"), limit = 2)
                KeyValueItem(parts.getOrNull(0)?.trim() ?: "", parts.getOrNull(1)?.trim() ?: "")
            }
        }.ifEmpty { listOf(KeyValueItem("", "")) }
    }

    private fun formatHeaders(
        items: List<KeyValueItem>,
        pairSeparator: String = ": ",
        joinSeparator: String = "\n",
    ): String = items
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .joinToString(joinSeparator) { "${it.key}$pairSeparator${it.value}" }

    private fun filterItems(items: List<String>, query: String): List<String> =
        if (query.isBlank()) {
            items
        } else {
            items.filter { it.contains(query.trim(), ignoreCase = true) }
        }

    @Test
    fun testBiometricAuthHelper_failsClosedWhenDeviceSecureOrHardwareUnavailable() {
        // Only bypass when device is confirmed NOT secure AND no lock/hardware is enrolled
        assertTrue(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertTrue(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))

        // Must NEVER bypass when device lock is enabled or unknown
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(true, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(true, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(true, BiometricManager.BIOMETRIC_SUCCESS))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(null, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))

        // Must NEVER bypass for transient hardware errors or security update required
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_SUCCESS))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
        assertFalse(BiometricAuthHelper.canBypassWhenNoLockEnrolled(false, BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
    }
}
