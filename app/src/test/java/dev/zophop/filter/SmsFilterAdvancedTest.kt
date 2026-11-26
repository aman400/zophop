package dev.zophop.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFilterAdvancedTest {
    @Test
    fun testWildcardToRegexConversion() {
        assertEquals("^.*HDFC.*$", SmsFilter.wildcardToRegex("*HDFC*"))
        assertEquals("^BANK.$", SmsFilter.wildcardToRegex("BANK?"))
        assertEquals("^\\+1555.*$", SmsFilter.wildcardToRegex("+1555*"))
        assertEquals("^TEST\\.SERVICE$", SmsFilter.wildcardToRegex("TEST.SERVICE"))
    }

    @Test
    fun testMatchesAllowlistWildcardsAndExact() {
        val allowlist = listOf("*BANK*", "+1987*", "VERIFY-OTP", ".*")

        assertTrue(SmsFilter.matchesAllowlist("HDFCBANK", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("MYBANKALERT", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("+1987654321", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("verify-otp", allowlist))

        assertFalse(SmsFilter.matchesAllowlist("", allowlist))
        assertFalse(SmsFilter.matchesAllowlist("   ", allowlist))

        val emptyList = emptyList<String>()
        // BUG-03 fix: empty allowlist now means "match all" (wildcard)
        assertTrue(SmsFilter.matchesAllowlist("ANY_SENDER", emptyList))
    }

    @Test
    fun testIsValidRegexEdgeCases() {
        assertTrue(SmsFilter.isValidRegex(".*"))
        assertTrue(SmsFilter.isValidRegex("(?i)otp|code"))
        assertTrue(SmsFilter.isValidRegex("\\b\\d{4,8}\\b"))

        assertFalse(SmsFilter.isValidRegex(""))
        assertFalse(SmsFilter.isValidRegex("   "))
        assertFalse(SmsFilter.isValidRegex("[a-z"))
        assertFalse(SmsFilter.isValidRegex("(?i"))
    }

    @Test
    fun testMatchesKeywordAndShouldForward() {
        val allowlist = listOf("BANK*", "NOTIFICATION")
        val keywordRegex = "(?i)otp|verification|security"

        assertTrue(SmsFilter.shouldForward("BANKALERT", "Your OTP code is 9481", allowlist, keywordRegex))
        assertTrue(SmsFilter.shouldForward("NOTIFICATION", "Security alert: login detected", allowlist, keywordRegex))

        // Sender matches, keyword does not
        assertFalse(SmsFilter.shouldForward("BANKALERT", "Good morning, have a nice day", allowlist, keywordRegex))

        // Keyword matches, sender does not
        assertFalse(SmsFilter.shouldForward("UNKNOWN_SENDER", "Your OTP code is 9481", allowlist, keywordRegex))
    }
}
