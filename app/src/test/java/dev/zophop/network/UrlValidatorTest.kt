package dev.zophop.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    @Test
    fun testHttpsUrlIsValid() {
        assertTrue(UrlValidator.isValidUrl("https://ntfy.sh"))
        assertTrue(UrlValidator.isValidUrl("https://ntfy.example.com/topic"))
        assertTrue(UrlValidator.isValidUrl("https://10.0.0.1:8080"))
    }

    @Test
    fun testHttpLocalhostIsValid() {
        assertTrue(UrlValidator.isValidUrl("http://localhost:8080"))
        assertTrue(UrlValidator.isValidUrl("http://127.0.0.1:8080/topic"))
        assertTrue(UrlValidator.isValidUrl("http://[::1]:8080"))
    }

    @Test
    fun testHttpPrivateIpWithoutOptIn() {
        assertFalse(UrlValidator.isValidUrl("http://192.168.1.50:8080", allowInsecurePrivateHttp = false))
        assertFalse(UrlValidator.isValidUrl("http://100.64.1.2:8080", allowInsecurePrivateHttp = false))
        assertFalse(UrlValidator.isValidUrl("http://10.0.0.5", allowInsecurePrivateHttp = false))
        assertFalse(UrlValidator.isValidUrl("http://my-node.ts.net:8080", allowInsecurePrivateHttp = false))
    }

    @Test
    fun testHttpPrivateIpWithOptIn() {
        assertTrue(UrlValidator.isValidUrl("http://192.168.1.50:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://100.64.1.2:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://10.0.0.5", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://172.20.0.1", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://server.local:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://my-node.ts.net:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://home-server.tailscale.net:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://device.lan:8080", allowInsecurePrivateHttp = true))
        assertTrue(UrlValidator.isValidUrl("http://internal.service.internal:8080", allowInsecurePrivateHttp = true))
    }

    @Test
    fun testPublicHttpIsAlwaysRejected() {
        assertFalse(UrlValidator.isValidUrl("http://example.com", allowInsecurePrivateHttp = true))
        assertFalse(UrlValidator.isValidUrl("http://8.8.8.8", allowInsecurePrivateHttp = true))
        assertFalse(UrlValidator.isValidUrl("http://ntfy.sh", allowInsecurePrivateHttp = true))
    }

    @Test
    fun testInvalidUrls() {
        assertFalse(UrlValidator.isValidUrl(""))
        assertFalse(UrlValidator.isValidUrl("not-a-url"))
        assertFalse(UrlValidator.isValidUrl("ftp://server.com"))
    }
}
