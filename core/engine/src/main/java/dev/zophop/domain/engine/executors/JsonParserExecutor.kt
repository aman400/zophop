package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private val SIZE_TOKENS = setOf("length", "size", "count")
private val TRUTHY_STRINGS = setOf("true", "1", "yes", "on")
private val FALSY_STRINGS = setOf("false", "0", "no", "off")

/**
 * Strategy executor for [NodeType.JSON_PARSER].
 * Extracts values from JSON strings using property path queries (e.g. `data.user.id`, `items[0].code`,
 * `matrix[0][1]`, `items[-1]`, `users[*].name`, `items.length`, `..code`)
 * and converts them to target types (`STRING`, `NUMBER`, `DOUBLE`, `BOOLEAN`, `JSON_STRING`, `COUNT`, `ARRAY`).
 * Also supports multi-field extraction via `mappings` / `extractions` configurations.
 */
class JsonParserExecutor : NodeExecutor {
    private fun cleanJsonInput(raw: String): String {
        var str = raw.trim()

        // Strip Markdown code block fences (e.g., ```json ... ```)
        if (str.startsWith("```")) {
            str =
                str
                    .removePrefix("```json")
                    .removePrefix("```JSON")
                    .removePrefix("```")
            if (str.endsWith("```")) {
                str = str.removeSuffix("```")
            }
            str = str.trim()
        }

        // If wrapped with non-JSON preamble/postscript, extract the outermost JSON root
        val firstBrace = str.indexOf('{')
        val firstBracket = str.indexOf('[')
        val firstJsonStart =
            when {
                firstBrace != -1 && firstBracket != -1 -> minOf(firstBrace, firstBracket)
                firstBrace != -1 -> firstBrace
                firstBracket != -1 -> firstBracket
                else -> -1
            }

        if (firstJsonStart > 0) {
            val lastBrace = str.lastIndexOf('}')
            val lastBracket = str.lastIndexOf(']')
            val lastJsonEnd = maxOf(lastBrace, lastBracket)
            if (lastJsonEnd > firstJsonStart) {
                str = str.substring(firstJsonStart, lastJsonEnd + 1).trim()
            }
        }

        return str
    }

    private fun tokenizePath(path: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inBracket = false
        var quoteChar: Char? = null

        for (c in path) {
            if (quoteChar != null) {
                if (c == quoteChar) quoteChar = null else sb.append(c)
                continue
            }
            when (c) {
                '\'', '"' -> quoteChar = c
                '[' -> {
                    flushToken(sb, tokens)
                    inBracket = true
                }
                ']' -> if (inBracket) {
                    tokens.add("[$sb]")
                    sb.clear()
                    inBracket = false
                }
                '.' -> if (!inBracket) flushToken(sb, tokens) else sb.append(c)
                else -> sb.append(c)
            }
        }
        flushToken(sb, tokens)
        return tokens.filter { it.isNotBlank() }
    }

    private fun flushToken(sb: StringBuilder, tokens: MutableList<String>) {
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
            sb.clear()
        }
    }

    private fun findRecursively(
        root: JsonElement?,
        targetKey: String,
    ): JsonElement? {
        if (root == null || root is JsonNull) return null
        return when (root) {
            is JsonObject -> findInJsonObject(root, targetKey)
            is JsonArray -> findInJsonArray(root, targetKey)
            else -> null
        }
    }

    private fun findInJsonObject(root: JsonObject, targetKey: String): JsonElement? {
        if (root.containsKey(targetKey)) return root[targetKey]
        for ((_, v) in root) {
            val found = findRecursively(v, targetKey)
            if (found != null && found !is JsonNull) return found
        }
        return null
    }

    private fun findInJsonArray(root: JsonArray, targetKey: String): JsonElement? {
        for (item in root) {
            val found = findRecursively(item, targetKey)
            if (found != null && found !is JsonNull) return found
        }
        return null
    }

    private fun evaluateBracketToken(
        current: JsonElement?,
        inner: String,
        tokens: List<String>,
        tokenIndex: Int,
    ): JsonElement? = when {
        inner == "*" || inner.isEmpty() -> {
            if (current is JsonArray) projectWildcard(current, tokens, tokenIndex) else null
        }
        inner.toIntOrNull() != null -> {
            val idx = inner.toInt()
            val nextItem = if (current is JsonArray) resolveArrayIndex(current, idx) else null
            evaluateTokens(nextItem, tokens, tokenIndex + 1)
        }
        else -> {
            val nextItem = if (current is JsonObject && current.containsKey(inner)) current[inner] else null
            evaluateTokens(nextItem, tokens, tokenIndex + 1)
        }
    }

    private fun projectWildcard(current: JsonArray, tokens: List<String>, tokenIndex: Int): JsonArray {
        val results = mutableListOf<JsonElement>()
        for (item in current) {
            val projected = evaluateTokens(item, tokens, tokenIndex + 1)
            if (projected != null && projected !is JsonNull) {
                results.add(projected)
            }
        }
        return JsonArray(results)
    }

    private fun resolveArrayIndex(current: JsonArray, idx: Int): JsonElement? {
        val resolvedIndex = if (idx < 0) current.size + idx else idx
        return if (resolvedIndex in 0 until current.size) current[resolvedIndex] else null
    }

    private fun evaluatePropertyToken(current: JsonElement?, token: String): JsonElement? = when (current) {
        is JsonObject -> when {
            current.containsKey(token) -> current[token]
            token in SIZE_TOKENS -> JsonPrimitive(current.size)
            token == "keys" -> JsonArray(current.keys.map { JsonPrimitive(it) })
            else -> null
        }
        is JsonArray -> evaluateArrayProperty(current, token)
        else -> null
    }

    private fun evaluateArrayProperty(current: JsonArray, token: String): JsonElement? {
        val idx = token.toIntOrNull()
        return when {
            idx != null -> resolveArrayIndex(current, idx)
            token in SIZE_TOKENS -> JsonPrimitive(current.size)
            else -> mapArrayElements(current, token)
        }
    }

    private fun mapArrayElements(current: JsonArray, token: String): JsonArray? {
        val results = mutableListOf<JsonElement>()
        for (item in current) {
            val obj = item as? JsonObject
            if (obj != null && obj.containsKey(token)) {
                val v = obj[token]
                if (v != null) {
                    results.add(v)
                }
            }
        }
        return if (results.isNotEmpty()) JsonArray(results) else null
    }

    private fun evaluateTokens(
        current: JsonElement?,
        tokens: List<String>,
        tokenIndex: Int,
    ): JsonElement? {
        if (current == null || current is JsonNull) return null
        if (tokenIndex >= tokens.size) return current

        val token = tokens[tokenIndex]
        return if (token.startsWith("[") && token.endsWith("]")) {
            val inner = token.substring(1, token.length - 1).trim().removeSurrounding("\"").removeSurrounding("'")
            evaluateBracketToken(current, inner, tokens, tokenIndex)
        } else {
            val nextItem = evaluatePropertyToken(current, token)
            evaluateTokens(nextItem, tokens, tokenIndex + 1)
        }
    }

    private fun extractJsonPath(
        root: JsonElement,
        rawPath: String,
    ): JsonElement? {
        val path = rawPath.trim().removePrefix("$").trim()
        if (path.isEmpty() || path == ".") return root

        return if (path.startsWith("..")) {
            val targetKey = path.removePrefix("..").trim()
            findRecursively(root, targetKey)
        } else {
            val tokens = tokenizePath(path)
            if (tokens.isEmpty()) root else evaluateTokens(root, tokens, 0)
        }
    }

    private fun convertDefaultValue(defaultValue: String, targetType: String): Any? =
        when (targetType.uppercase()) {
            "NUMBER", "INTEGER", "INT" -> defaultValue.toIntOrNull() ?: defaultValue.toDoubleOrNull()
            "DOUBLE", "FLOAT" -> defaultValue.toDoubleOrNull()
            "BOOLEAN", "BOOL" -> defaultValue.lowercase() in TRUTHY_STRINGS
            else -> defaultValue
        }

    private fun convertToNumber(value: JsonElement, defaultValue: String): Any? = when (value) {
        is JsonPrimitive ->
            value.intOrNull
                ?: value.doubleOrNull?.toInt()
                ?: value.content.trim().toIntOrNull()
                ?: defaultValue.toIntOrNull()
        else -> defaultValue.toIntOrNull()
    }

    private fun convertToDouble(value: JsonElement, defaultValue: String): Double? = when (value) {
        is JsonPrimitive -> value.doubleOrNull ?: value.content.trim().toDoubleOrNull() ?: defaultValue.toDoubleOrNull()
        else -> defaultValue.toDoubleOrNull()
    }

    private fun convertToBoolean(value: JsonElement, defaultValue: String): Boolean = when (value) {
        is JsonPrimitive -> {
            value.booleanOrNull
                ?: (value.doubleOrNull?.let { it != 0.0 })
                ?: when (value.content.trim().lowercase()) {
                    in TRUTHY_STRINGS -> true
                    in FALSY_STRINGS -> false
                    else -> defaultValue.toBooleanStrictOrNull() ?: false
                }
        }
        else -> defaultValue.toBooleanStrictOrNull() ?: false
    }

    private fun convertToCount(value: JsonElement): Int = when (value) {
        is JsonArray -> value.size
        is JsonObject -> value.size
        is JsonPrimitive -> value.content.length
        else -> 1
    }

    private fun convertToArray(value: JsonElement): String = when (value) {
        is JsonArray -> value.toString()
        else -> buildJsonArray { add(value) }.toString()
    }

    private fun convertType(
        value: JsonElement?,
        targetType: String,
        defaultValue: String,
    ): Any? {
        if (value == null || value is JsonNull) {
            return if (defaultValue.isNotBlank()) convertDefaultValue(defaultValue, targetType) else null
        }
        return when (targetType.uppercase()) {
            "STRING" -> if (value is JsonPrimitive) value.content else value.toString()
            "NUMBER", "INTEGER", "INT" -> convertToNumber(value, defaultValue)
            "DOUBLE", "FLOAT" -> convertToDouble(value, defaultValue)
            "BOOLEAN", "BOOL" -> convertToBoolean(value, defaultValue)
            "COUNT", "LENGTH", "SIZE" -> convertToCount(value)
            "ARRAY", "LIST" -> convertToArray(value)
            "JSON_STRING" -> value.toString()
            else -> when (value) {
                is JsonPrimitive -> {
                    if (value.isString) {
                        value.content
                    } else {
                        value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                    }
                }
                else -> value.toString()
            }
        }
    }

    private fun resolveRawInput(config: JsonObject, context: ExecutionContext): String {
        val rawInputVariable = config.optString("inputVariable", "").trim()
        val inputVariable = context.interpolate(rawInputVariable).trim()
        return when {
            inputVariable.isNotBlank() && context.get(inputVariable) != null -> context.getString(inputVariable, "")
            context.getString("http_response", "").isNotBlank() -> context.getString("http_response", "")
            context.getString("formatted_payload", "").isNotBlank() -> context.getString("formatted_payload", "")
            context.getString("payload", "").isNotBlank() -> context.getString("payload", "")
            context.getString("body", "").isNotBlank() -> context.getString("body", "")
            context.getString("text", "").isNotBlank() -> context.getString("text", "")
            else -> ""
        }
    }

    private fun extractMultiMappings(config: JsonObject): List<Triple<String, String, String>> {
        val mappingsObj = config.optJsonObject("mappings")
        val mappingsArr = config.optJsonArray("mappings") ?: config.optJsonArray("extractions")
        val multiExtractions = mutableListOf<Triple<String, String, String>>()

        if (mappingsObj != null) {
            for ((outK, pElem) in mappingsObj) {
                val p = (pElem as? JsonPrimitive)?.content ?: pElem.toString()
                if (outK.isNotBlank() && p.isNotBlank()) {
                    multiExtractions.add(Triple(p, outK, "AUTO"))
                }
            }
        } else if (mappingsArr != null) {
            for (i in 0 until mappingsArr.size) {
                val item = mappingsArr.optJsonObject(i) ?: continue
                val p = item.optString("jsonKey", item.optString("path", ""))
                val outK = item.optString("outputKey", item.optString("key", p))
                val tType = item.optString("targetType", "AUTO")
                if (p.isNotBlank() && outK.isNotBlank()) {
                    multiExtractions.add(Triple(p, outK, tType))
                }
            }
        }
        return multiExtractions
    }

    private fun executeMulti(
        multiExtractions: List<Triple<String, String, String>>,
        jsonRoot: JsonElement,
        context: ExecutionContext,
        outputKey: String,
        defaultValue: String,
    ): NodeExecutionResult {
        val extractedKeys = mutableListOf<String>()
        for ((path, outK, tType) in multiExtractions) {
            val interpPath = context.interpolate(path).trim()
            val extracted = extractJsonPath(jsonRoot, interpPath)
            val converted = convertType(extracted, tType, "")
            if (converted != null) {
                context.set(outK, converted)
                extractedKeys.add(outK)
            }
        }
        return if (extractedKeys.isNotEmpty()) {
            NodeExecutionResult.Success(logMessage = "Parsed ${extractedKeys.size} JSON fields: $extractedKeys")
        } else if (defaultValue.isNotBlank()) {
            context.set(outputKey, defaultValue)
            NodeExecutionResult.Success(
                logMessage = "No mapped fields found, used default -> '$outputKey' = $defaultValue",
            )
        } else {
            NodeExecutionResult.Failed("None of the mapped JSON paths were found in payload")
        }
    }

    private fun executeSingle(
        jsonRoot: JsonElement,
        jsonKey: String,
        targetType: String,
        defaultValue: String,
        outputKey: String,
        context: ExecutionContext,
    ): NodeExecutionResult {
        val extracted = extractJsonPath(jsonRoot, jsonKey)
        val converted = convertType(extracted, targetType, defaultValue)

        return if (converted != null) {
            context.set(outputKey, converted)
            if (outputKey != "json_output") {
                context.set("json_output", converted)
            }
            NodeExecutionResult.Success(logMessage = "Parsed '$jsonKey' [$targetType] -> '$outputKey' = $converted")
        } else if (defaultValue.isNotBlank()) {
            context.set(outputKey, defaultValue)
            if (outputKey != "json_output") {
                context.set("json_output", defaultValue)
            }
            NodeExecutionResult.Success(
                logMessage = "Parsed '$jsonKey' null/missing, used default -> '$outputKey' = $defaultValue",
            )
        } else {
            NodeExecutionResult.Failed("JSON path '$jsonKey' not found or null")
        }
    }

    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseJsonObjectSafe(node.configJson)
        val rawJsonKey = config.optString("jsonKey", "code").trim()
        val jsonKey = context.interpolate(rawJsonKey).trim()
        val outputKey = config.optString("outputKey", rawJsonKey).ifEmpty { rawJsonKey }.trim()
        val targetType = config.optString("targetType", "AUTO")
        val defaultValue = config.optString("defaultValue", "")

        val rawInput = resolveRawInput(config, context)
        if (rawInput.isBlank()) {
            return NodeExecutionResult.Failed("Input JSON payload is empty")
        }

        return try {
            val trimmedInput = cleanJsonInput(rawInput)
            val jsonRoot: JsonElement =
                try {
                    AppJson.parseToJsonElement(trimmedInput)
                } catch (_: Exception) {
                    buildJsonObject { put("value", trimmedInput) }
                }

            val multiExtractions = extractMultiMappings(config)
            if (multiExtractions.isNotEmpty()) {
                executeMulti(multiExtractions, jsonRoot, context, outputKey, defaultValue)
            } else {
                executeSingle(jsonRoot, jsonKey, targetType, defaultValue, outputKey, context)
            }
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("JSON parsing error for '$jsonKey': ${e.message}")
        } catch (e: IllegalStateException) {
            NodeExecutionResult.Failed("JSON parsing error for '$jsonKey': ${e.message}")
        }
    }
}
