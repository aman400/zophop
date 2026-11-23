package dev.zophop.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAndUrlValidationTest {
    @Test
    fun testValidPublicHttpsUrlsAllowed() {
        assertTrue(UrlValidator.isValidUrl("https://ntfy.sh/my-topic"))
        assertTrue(UrlValidator.isValidUrl("https://discord.com/api/webhooks/123/abc"))
        assertTrue(UrlValidator.isValidUrl("https://api.telegram.org/bot12345/sendMessage"))
        assertTrue(UrlValidator.isValidUrl("https://example.com/api?param=value"))
    }

    @Test
    fun testDangerousSchemesRejected() {
        assertFalse(UrlValidator.isValidUrl("file:///etc/passwd"))
        assertFalse(UrlValidator.isValidUrl("content://media/external/images"))
        assertFalse(UrlValidator.isValidUrl("javascript:alert(1)"))
        assertFalse(UrlValidator.isValidUrl("ftp://files.example.com"))
        assertFalse(UrlValidator.isValidUrl("data:text/plain;base64,SGVsbG8="))
    }

    @Test
    fun testHttpUrlsRejectedByDefaultUnlessLocalhost() {
        // Plain HTTP to public site rejected by default
        assertFalse(UrlValidator.isValidUrl("http://example.com/webhook"))

        // Localhost allowed over HTTP for dev testing
        assertTrue(UrlValidator.isValidUrl("http://localhost:8080/webhook"))
        assertTrue(UrlValidator.isValidUrl("http://127.0.0.1:8080/webhook"))
        assertTrue(UrlValidator.isValidUrl("http://[::1]:8080/webhook"))
    }

    @Test
    fun testInsecurePrivateHttpAllowedWhenOptInEnabled() {
        val privateUrl1 = "http://10.0.0.5:8080/hook"
        val privateUrl2 = "http://192.168.1.100:8080/hook"
        val tailscaleUrl = "http://100.64.1.2:8080/hook"

        // False when opt-in disabled
        assertFalse(UrlValidator.isValidUrl(privateUrl1, allowInsecurePrivateHttp = false))
        assertFalse(UrlValidator.isValidUrl(privateUrl2, allowInsecurePrivateHttp = false))
        assertFalse(UrlValidator.isValidUrl(tailscaleUrl, allowInsecurePrivateHttp = false))

        // True when opt-in enabled
        assertTrue(UrlValidator.isValidUrl(privateUrl1, allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl(privateUrl2, allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl(tailscaleUrl, allowInsecurePrivateHttp = true))
    }

    @Test
    fun testMalformedUrlsRejected() {
        assertFalse(UrlValidator.isValidUrl(""))
        assertFalse(UrlValidator.isValidUrl("   "))
        assertFalse(UrlValidator.isValidUrl("not_a_url"))
        assertFalse(UrlValidator.isValidUrl("https://"))
        assertFalse(UrlValidator.isValidUrl("ht tp://bad.com"))
    }
}
