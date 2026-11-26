package dev.zophop.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFilterTest {
    @Test
    fun testWildcardToRegexConversion() {
        assertEquals("^.*HDFC.*$", SmsFilter.wildcardToRegex("*HDFC*"))
        assertEquals("^ICICI-.*$", SmsFilter.wildcardToRegex("ICICI-*"))
        assertEquals("^BANK.$", SmsFilter.wildcardToRegex("BANK?"))
        assertEquals("^exact$", SmsFilter.wildcardToRegex("exact"))
        assertEquals("^special\\.char\\+test$", SmsFilter.wildcardToRegex("special.char+test"))
    }

    @Test
    fun testDefaultDotStarMatchesAnySenderAndAnyBody() {
        assertTrue(SmsFilter.matchesAllowlist("ANY_SENDER", listOf(".*")))
        assertTrue(SmsFilter.matchesAllowlist("ANY_SENDER", listOf("*")))
        assertTrue(SmsFilter.matchesKeyword("Hello world, any message body text", ".*"))
        assertTrue(
            SmsFilter.shouldForward(
                sender = "ANY_SENDER",
                body = "Hello world, any message body text",
                allowlist = listOf(".*"),
                keywordRegexStr = ".*",
            ),
        )
    }

    @Test
    fun testMatchesAllowlist() {
        val allowlist = listOf("*HDFC*", "*ICICI*", "BANK-?")

        assertTrue(SmsFilter.matchesAllowlist("AX-HDFCBK", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("hdfc-bank", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("ICICI-OTP", allowlist))
        assertTrue(SmsFilter.matchesAllowlist("BANK-1", allowlist))
        assertFalse(SmsFilter.matchesAllowlist("BANK-12", allowlist))
        assertFalse(SmsFilter.matchesAllowlist("UNKNOWN-SENDER", allowlist))
    }

    @Test
    fun testEmptyAllowlist() {
        // BUG-03 FIX: an empty allowlist now means "match all" (wildcard), so forwarding
        // is not blocked when no explicit allowlist is configured.
        assertTrue(
            "Empty list must match any sender (BUG-03 fix)",
            SmsFilter.matchesAllowlist("HDFCBK", emptyList()),
        )
        // Whitespace-only entries are trimmed and filtered, resulting in an empty list → match all
        assertTrue(
            "Whitespace-only entries must match any sender (BUG-03 fix)",
            SmsFilter.matchesAllowlist("HDFCBK", listOf("   ")),
        )
    }

    @Test
    fun testIsValidRegex() {
        assertTrue(SmsFilter.isValidRegex(".*"))
        assertTrue(SmsFilter.isValidRegex("(?i)(otp|code)"))
        assertTrue(SmsFilter.isValidRegex("^[0-9]+$"))
        assertFalse(SmsFilter.isValidRegex("[unclosed-group"))
        assertFalse(SmsFilter.isValidRegex(""))
    }

    @Test
    fun testMatchesKeyword() {
        val defaultKeywordRegex = ".*"

        assertTrue(SmsFilter.matchesKeyword("Your OTP for transaction is 123456", defaultKeywordRegex))
        assertTrue(SmsFilter.matchesKeyword("Use 9876 as your one time password", defaultKeywordRegex))
        assertTrue(SmsFilter.matchesKeyword("Your verification code is 4455", defaultKeywordRegex))
        assertTrue(SmsFilter.matchesKeyword("Good morning! How are you?", defaultKeywordRegex))
    }

    @Test
    fun testShouldForwardBothMustMatch() {
        val allowlist = listOf("*HDFC*", "*ICICI*")
        val keywordRegex = "(?i)(otp|one.?time|verification|code)"

        // Both match -> True
        assertTrue(
            SmsFilter.shouldForward(
                sender = "VM-HDFCBK",
                body = "Your OTP is 654321",
                allowlist = allowlist,
                keywordRegexStr = keywordRegex,
            ),
        )

        // Sender matches but body doesn't -> False
        assertFalse(
            SmsFilter.shouldForward(
                sender = "VM-HDFCBK",
                body = "Welcome to HDFC Bank internet banking",
                allowlist = allowlist,
                keywordRegexStr = keywordRegex,
            ),
        )

        // Body matches but sender doesn't -> False
        assertFalse(
            SmsFilter.shouldForward(
                sender = "SPAM-SENDER",
                body = "Your OTP is 654321",
                allowlist = allowlist,
                keywordRegexStr = keywordRegex,
            ),
        )

        // Neither matches -> False
        assertFalse(
            SmsFilter.shouldForward(
                sender = "SPAM-SENDER",
                body = "Hello World",
                allowlist = allowlist,
                keywordRegexStr = keywordRegex,
            ),
        )
    }
}
