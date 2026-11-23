package dev.zophop.domain.engine

import dev.zophop.plugin.api.ExecutionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionContextTest {
    @Test
    fun testGetAndSetVariables() {
        val context = ExecutionContext()
        context.set("key1", "value1")
        context.set("key2", 100)
        context.set("key3", true)

        assertEquals("value1", context.get("key1"))
        assertEquals(100, context.get("key2"))
        assertEquals(true, context.get("key3"))
        assertNull(context.get("non_existent"))
    }

    @Test
    fun testTypedGetters() {
        val context =
            ExecutionContext(
                mapOf(
                    "str" to "hello",
                    "num" to "42",
                    "bool" to "true",
                    "intVal" to 99,
                ),
            )

        assertEquals("hello", context.getString("str"))
        assertEquals("42", context.getString("num"))
        assertEquals("default", context.getString("missing", "default"))
    }

    @Test
    fun testInterpolateSimpleVariables() {
        val context =
            ExecutionContext(
                mapOf(
                    "sender" to "BANK_SMS",
                    "code" to "987654",
                ),
            )

        val template = "Received code \${code} from \${sender}"
        val result = context.interpolate(template)

        assertEquals("Received code 987654 from BANK_SMS", result)
    }

    @Test
    fun testInterpolateMissingVariablePreservesPlaceholder() {
        val context = ExecutionContext(mapOf("existing" to "OK"))

        val template = "Value: \${existing}, Missing: \${unknown}"
        val result = context.interpolate(template)

        assertEquals("Value: OK, Missing: \${unknown}", result)
    }

    @Test
    fun testInterpolateWithMultipleOccurrences() {
        val context = ExecutionContext(mapOf("tag" to "OTP"))

        val template = "[\${tag}] Your \${tag} code is ready."
        val result = context.interpolate(template)

        assertEquals("[OTP] Your OTP code is ready.", result)
    }
}
