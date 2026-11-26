package dev.zophop.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlCommandParserTest {
    @Test
    fun parseSimpleGetWithQueryParams() {
        val result = CurlCommandParser.parse("curl https://api.example.com/v1/items?page=2&limit=50")
        assertTrue(result is CurlParseResult.Success)
        val cmd = (result as CurlParseResult.Success).command
        assertEquals("https://api.example.com/v1/items", cmd.url)
        assertEquals("GET", cmd.method)
        assertEquals("page=2&limit=50", cmd.queryParams)
        assertEquals("NONE", cmd.bodyType)
        assertEquals("", cmd.bodyTemplate)
    }

    @Test
    fun parseMultilinePostWithHeadersAndJsonBody() {
        val raw =
            """
            curl -X POST "https://hooks.example.com/notify?env=prod" \
              -H "Content-Type: application/json" \
              -H 'Authorization: Bearer tok_12345' \
              -d '{"sender":"${'$'}{sender}","code":"${'$'}{code}"}'
            """.trimIndent()

        val result = CurlCommandParser.parse(raw)
        assertTrue(result is CurlParseResult.Success)
        val cmd = (result as CurlParseResult.Success).command
        assertEquals("https://hooks.example.com/notify", cmd.url)
        assertEquals("POST", cmd.method)
        assertEquals("env=prod", cmd.queryParams)
        assertEquals("JSON", cmd.bodyType)
        assertEquals("""{"sender":"${'$'}{sender}","code":"${'$'}{code}"}""", cmd.bodyTemplate)
        assertTrue(cmd.headers.contains("Content-Type: application/json"))
        assertTrue(cmd.headers.contains("Authorization: Bearer tok_12345"))
    }

    @Test
    fun parseImplicitPostWhenDataRawProvided() {
        val raw = """curl --url https://api.example.com/events --data-raw '{"event":"ping"}'"""
        val result = CurlCommandParser.parse(raw)
        assertTrue(result is CurlParseResult.Success)
        val cmd = (result as CurlParseResult.Success).command
        assertEquals("POST", cmd.method)
        assertEquals("JSON", cmd.bodyType)
        assertEquals("""{"event":"ping"}""", cmd.bodyTemplate)
    }

    @Test
    fun parseBasicAuthUserAgentCookieAndPatchMethod() {
        val raw =
            """
            curl -sSL -X PATCH https://api.example.com/users/42 \
              -u alice:s3cr3t \
              -A "ZopHopAgent/1.0" \
              -e "https://app.example.com" \
              -b "session=abc123" \
              --data-binary "active=true"
            """.trimIndent()

        val result = CurlCommandParser.parse(raw)
        assertTrue(result is CurlParseResult.Success)
        val cmd = (result as CurlParseResult.Success).command
        assertEquals("PATCH", cmd.method)
        assertEquals("RAW", cmd.bodyType)
        assertEquals("active=true", cmd.bodyTemplate)
        assertTrue(cmd.headers.contains("Authorization: Basic YWxpY2U6czNjcjN0"))
        assertTrue(cmd.headers.contains("User-Agent: ZopHopAgent/1.0"))
        assertTrue(cmd.headers.contains("Referer: https://app.example.com"))
        assertTrue(cmd.headers.contains("Cookie: session=abc123"))
    }

    @Test
    fun parseGetFlagMovesDataIntoQueryParams() {
        val raw = """curl -G https://api.example.com/search?type=sms -d "q=bank otp" -d "limit=5""""
        val result = CurlCommandParser.parse(raw)
        assertTrue(result is CurlParseResult.Success)
        val cmd = (result as CurlParseResult.Success).command
        assertEquals("GET", cmd.method)
        assertEquals("NONE", cmd.bodyType)
        assertEquals("", cmd.bodyTemplate)
        assertEquals("type=sms&q=bank otp&limit=5", cmd.queryParams)
    }

    @Test
    fun parseUrlEncodedFormDataAndDeleteAndHead() {
        val formRes = CurlCommandParser.parse("""curl https://api.example.com/token --data-urlencode "grant_type=client_credentials" --data-urlencode "scope=read write"""")
        assertTrue(formRes is CurlParseResult.Success)
        val formCmd = (formRes as CurlParseResult.Success).command
        assertEquals("POST", formCmd.method)
        assertEquals("FORM_DATA", formCmd.bodyType)
        assertEquals("grant_type=client_credentials&scope=read%20write", formCmd.bodyTemplate)

        val delRes = CurlCommandParser.parse("curl -XDELETE https://api.example.com/item/1")
        assertTrue(delRes is CurlParseResult.Success)
        assertEquals("DELETE", (delRes as CurlParseResult.Success).command.method)

        val headRes = CurlCommandParser.parse("curl -I https://api.example.com/health")
        assertTrue(headRes is CurlParseResult.Success)
        assertEquals("HEAD", (headRes as CurlParseResult.Success).command.method)
    }

    @Test
    fun parseInvalidInputsReturnHelpfulErrors() {
        assertTrue(CurlCommandParser.parse("   ") is CurlParseResult.Error)
        assertTrue(CurlCommandParser.parse("wget https://example.com") is CurlParseResult.Error)
        assertTrue(CurlCommandParser.parse("curl -X POST https://example.com -d 'unclosed") is CurlParseResult.Error)
        assertTrue(CurlCommandParser.parse("curl -X POST -H 'X-Test: 1'") is CurlParseResult.Error)
    }
}
