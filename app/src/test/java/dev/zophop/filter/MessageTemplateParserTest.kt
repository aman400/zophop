package dev.zophop.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTemplateParserTest {
    @Test
    fun testAutoExtractOtpCode() {
        val text = "Your HDFC Bank OTP for transaction is 482910. Valid for 10 mins."
        val code = MessageTemplateParser.extractOtpCode(text)
        assertEquals("482910", code)
    }

    @Test
    fun testTemplateFormattingWithSenderAndBody() {
        val formatted =
            MessageTemplateParser.formatPayload(
                sender = "VM-HDFCBK",
                body = "Your OTP is 987654",
                template = "[\${sender}] Code: \${code}",
            )
        assertEquals("[VM-HDFCBK] Code: 987654", formatted)
    }

    @Test
    fun testTemplateFormattingWithCurlyBraceSyntax() {
        val formatted =
            MessageTemplateParser.formatPayload(
                sender = "VM-HDFCBK",
                body = "Your OTP is 987654",
                template = "[{sender}] Code: {code} / {otp}",
            )
        assertEquals("[VM-HDFCBK] Code: 987654 / 987654", formatted)
    }

    @Test
    fun testTemplateFormattingWithMessageAndTextAliases() {
        val formattedMessage =
            MessageTemplateParser.formatPayload(
                sender = "BANK",
                body = "Alert 123456",
                template = "\${sender}: \${message}",
            )
        assertEquals("BANK: Alert 123456", formattedMessage)

        val formattedText =
            MessageTemplateParser.formatPayload(
                sender = "BANK",
                body = "Alert 123456",
                template = "{sender}: {text}",
            )
        assertEquals("BANK: Alert 123456", formattedText)
    }

    @Test
    fun testRegexCaptureGroupsInBody() {
        val bodyText = "Alert: Paid Rs 150 to Merchant Amazon. OTP is 445566."
        val bodyRegex = "Paid Rs (\\d+) to Merchant ([A-Za-z]+)"
        val template = "Paid: \${group1} | Merchant: \${group2} | Code: \${code}"

        val formatted =
            MessageTemplateParser.formatPayload(
                sender = "AXISBK",
                body = bodyText,
                bodyParseRegex = bodyRegex,
                template = template,
            )

        assertEquals("Paid: 150 | Merchant: Amazon | Code: 445566", formatted)
    }

    @Test
    fun testRegexCaptureGroupsInSender() {
        val sender = "VM-HDFCBK"
        val senderRegex = "^([A-Z]{2})-([A-Z]+)$"
        val template = "Gate: \${senderGroup1} | Provider: \${senderGroup2} | \${body}"

        val formatted =
            MessageTemplateParser.formatPayload(
                sender = sender,
                body = "OTP is 112233",
                senderRegex = senderRegex,
                template = template,
            )

        assertEquals("Gate: VM | Provider: HDFCBK | OTP is 112233", formatted)
    }

    @Test
    fun testTemplateFormattingBlocksSecondOrderExpansion() {
        val formatted =
            MessageTemplateParser.formatPayload(
                sender = "REAL_SENDER",
                body = "Use code \${sender} or {code} (OTP 654321)",
                template = "Msg: \${body} | Code: \${code}",
            )

        assertEquals("Msg: Use code \${sender} or {code} (OTP 654321) | Code: 654321", formatted)
    }
}
