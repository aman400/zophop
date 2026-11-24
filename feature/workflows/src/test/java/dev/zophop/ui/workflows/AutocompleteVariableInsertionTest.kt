package dev.zophop.ui.workflows

import org.junit.Assert.assertEquals
import org.junit.Test

class AutocompleteVariableInsertionTest {

    @Test
    fun testInsertVariableAtCursorInEmptyTemplate() {
        val (text, cursor) = insertVariableAtCursor(
            currentText = "",
            selectionStart = 0,
            selectionEnd = 0,
            variable = "code",
            insertRaw = false,
        )
        assertEquals("\${code}", text)
        assertEquals("\${code}".length, cursor)
    }

    @Test
    fun testInsertVariableAtEndPreservesExistingTemplateText() {
        val current = "OTP: "
        val (text, cursor) = insertVariableAtCursor(
            currentText = current,
            selectionStart = current.length,
            selectionEnd = current.length,
            variable = "code",
            insertRaw = false,
        )
        assertEquals("OTP: \${code}", text)
        assertEquals("OTP: \${code}".length, cursor)
    }

    @Test
    fun testInsertVariableInMiddlePreservesBothPrefixAndSuffix() {
        val current = "Alert:  is down"
        val cursorIndex = 7 // right after "Alert: "
        val (text, cursor) = insertVariableAtCursor(
            currentText = current,
            selectionStart = cursorIndex,
            selectionEnd = cursorIndex,
            variable = "server",
            insertRaw = false,
        )
        assertEquals("Alert: \${server} is down", text)
        assertEquals("Alert: \${server}".length, cursor)
    }

    @Test
    fun testInsertVariableWithMultipleExistingVariablesPreservesAllPriorAndSubsequentVariables() {
        val current = "Code: \${code}, User: \${user}, Action: "
        val (text, _) = insertVariableAtCursor(
            currentText = current,
            selectionStart = current.length,
            selectionEnd = current.length,
            variable = "action_name",
            insertRaw = false,
        )
        // Must NOT delete earlier variables or truncate existing template content
        assertEquals("Code: \${code}, User: \${user}, Action: \${action_name}", text)
    }

    @Test
    fun testInsertVariableCompletingIncompletePlaceholderPreservesSurroundingText() {
        // Case 1: Incomplete "${co" at end
        val case1 = "Code: \${co"
        val (text1, _) = insertVariableAtCursor(
            currentText = case1,
            selectionStart = case1.length,
            selectionEnd = case1.length,
            variable = "code",
            insertRaw = false,
        )
        assertEquals("Code: \${code}", text1)

        // Case 2: Incomplete "${co" in middle of sentence
        val case2 = "Code: \${co and keep this"
        val (text2, _) = insertVariableAtCursor(
            currentText = case2,
            selectionStart = 10, // right after "co"
            selectionEnd = 10,
            variable = "code",
            insertRaw = false,
        )
        assertEquals("Code: \${code} and keep this", text2)

        // Case 3: Just "$"
        val case3 = "Total: $"
        val (text3, _) = insertVariableAtCursor(
            currentText = case3,
            selectionStart = case3.length,
            selectionEnd = case3.length,
            variable = "amount",
            insertRaw = false,
        )
        assertEquals("Total: \${amount}", text3)
    }

    @Test
    fun testInsertVariableReplacingActiveSelection() {
        val current = "Please replace [TARGET] now"
        val start = current.indexOf("[TARGET]")
        val end = start + "[TARGET]".length
        val (text, _) = insertVariableAtCursor(
            currentText = current,
            selectionStart = start,
            selectionEnd = end,
            variable = "otp_code",
            insertRaw = false,
        )
        assertEquals("Please replace \${otp_code} now", text)
    }

    @Test
    fun testInsertRawVariableSingleIdentifierMode() {
        // Blank field sets variable directly
        val (text1, _) = insertVariableAtCursor(
            currentText = "",
            selectionStart = 0,
            selectionEnd = 0,
            variable = "status",
            insertRaw = true,
        )
        assertEquals("status", text1)

        // Existing single identifier gets cleanly replaced
        val (text2, _) = insertVariableAtCursor(
            currentText = "old_var",
            selectionStart = 7,
            selectionEnd = 7,
            variable = "new_var",
            insertRaw = true,
        )
        assertEquals("new_var", text2)
    }

    @Test
    fun testInsertVariableWithMaxLengthConstraint() {
        val (text, cursor) = insertVariableAtCursor(
            currentText = "Msg: ",
            selectionStart = 5,
            selectionEnd = 5,
            variable = "very_long_variable_name",
            insertRaw = false,
            maxLength = 15,
        )
        assertEquals(15, text.length)
        assertEquals("Msg: \${very_lon", text)
        assertEquals(15, cursor)
    }
}
