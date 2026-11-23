package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
        root: Any?,
        targetKey: String,
    ): Any? {
        if (root == null || root == JSONObject.NULL) return null
        return when (root) {
            is JSONObject -> findInJsonObject(root, targetKey)
            is JSONArray -> findInJsonArray(root, targetKey)
            else -> null
        }
    }

    private fun findInJsonObject(root: JSONObject, targetKey: String): Any? {
        if (root.has(targetKey)) return root.get(targetKey)
        val keys = root.keys()
        while (keys.hasNext()) {
            val found = findRecursively(root.opt(keys.next()), targetKey)
            if (found != null && found != JSONObject.NULL) return found
        }
        return null
    }

    private fun findInJsonArray(root: JSONArray, targetKey: String): Any? {
        for (i in 0 until root.length()) {
            val found = findRecursively(root.opt(i), targetKey)
            if (found != null && found != JSONObject.NULL) return found
        }
        return null
    }

    private fun evaluateBracketToken(
        current: Any?,
        inner: String,
        tokens: List<String>,
        tokenIndex: Int,
    ): Any? = when {
        inner == "*" || inner.isEmpty() -> {
            if (current is JSONArray) projectWildcard(current, tokens, tokenIndex) else null
        }
        inner.toIntOrNull() != null -> {
            val idx = inner.toInt()
            val nextItem = if (current is JSONArray) resolveArrayIndex(current, idx) else null
            evaluateTokens(nextItem, tokens, tokenIndex + 1)
        }
        else -> {
            val nextItem = if (current is JSONObject && current.has(inner)) current.get(inner) else null
            evaluateTokens(nextItem, tokens, tokenIndex + 1)
        }
    }

    private fun projectWildcard(current: JSONArray, tokens: List<String>, tokenIndex: Int): JSONArray {
        val results = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.opt(i)
            val projected = evaluateTokens(item, tokens, tokenIndex + 1)
            if (projected != null && projected != JSONObject.NULL) {
                results.put(projected)
            }
        }
        return results
    }

    private fun resolveArrayIndex(current: JSONArray, idx: Int): Any? {
        val resolvedIndex = if (idx < 0) current.length() + idx else idx
        return if (resolvedIndex in 0 until current.length()) current.get(resolvedIndex) else null
    }

    private fun evaluatePropertyToken(current: Any?, token: String): Any? = when (current) {
        is JSONObject -> when {
            current.has(token) -> current.get(token)
            token in SIZE_TOKENS -> current.length()
            token == "keys" -> JSONArray(current.keys().asSequence().toList())
            else -> null
        }
        is JSONArray -> evaluateArrayProperty(current, token)
        else -> null
    }

    private fun evaluateArrayProperty(current: JSONArray, token: String): Any? {
        val idx = token.toIntOrNull()
        return when {
            idx != null -> resolveArrayIndex(current, idx)
            token in SIZE_TOKENS -> current.length()
            else -> mapArrayElements(current, token)
        }
    }

    private fun mapArrayElements(current: JSONArray, token: String): JSONArray? {
        val results = JSONArray()
        for (i in 0 until current.length()) {
            val obj = current.optJSONObject(i)
            if (obj != null && obj.has(token)) {
                results.put(obj.get(token))
            }
        }
        return if (results.length() > 0) results else null
    }

    private fun evaluateTokens(
        current: Any?,
        tokens: List<String>,
        tokenIndex: Int,
    ): Any? {
        if (current == null || current == JSONObject.NULL) return null
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
        root: Any,
        rawPath: String,
    ): Any? {
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

    private fun convertToNumber(value: Any, defaultValue: String): Any? = when (value) {
        is Number -> value.toInt()
        is Boolean -> if (value) 1 else 0
        else ->
            value.toString().trim().toIntOrNull()
                ?: value.toString().trim().toDoubleOrNull()?.toInt()
                ?: defaultValue.toIntOrNull()
    }

    private fun convertToDouble(value: Any, defaultValue: String): Double? = when (value) {
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        else -> value.toString().trim().toDoubleOrNull() ?: defaultValue.toDoubleOrNull()
    }

    private fun convertToBoolean(value: Any, defaultValue: String): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        else -> {
            val str = value.toString().trim().lowercase()
            when {
                str in TRUTHY_STRINGS -> true
                str in FALSY_STRINGS -> false
                else -> defaultValue.toBooleanStrictOrNull() ?: false
            }
        }
    }

    private fun convertToCount(value: Any): Int = when (value) {
        is JSONArray -> value.length()
        is JSONObject -> value.length()
        is Collection<*> -> value.size
        is String -> value.length
        else -> 1
    }

    private fun convertToArray(value: Any): String = when (value) {
        is JSONArray -> value.toString()
        is JSONObject -> JSONArray().put(value).toString()
        is Collection<*> -> JSONArray(value).toString()
        else -> JSONArray().put(value.toString()).toString()
    }

    private fun convertType(
        value: Any?,
        targetType: String,
        defaultValue: String,
    ): Any? {
        if (value == null || value == JSONObject.NULL) {
            return if (defaultValue.isNotBlank()) convertDefaultValue(defaultValue, targetType) else null
        }
        return when (targetType.uppercase()) {
            "STRING" -> value.toString()
            "NUMBER", "INTEGER", "INT" -> convertToNumber(value, defaultValue)
            "DOUBLE", "FLOAT" -> convertToDouble(value, defaultValue)
            "BOOLEAN", "BOOL" -> convertToBoolean(value, defaultValue)
            "COUNT", "LENGTH", "SIZE" -> convertToCount(value)
            "ARRAY", "LIST" -> convertToArray(value)
            "JSON_STRING" -> value.toString()
            else -> if (value is JSONObject || value is JSONArray) value.toString() else value
        }
    }

    private fun resolveRawInput(config: JSONObject, context: ExecutionContext): String {
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

    private fun extractMultiMappings(config: JSONObject): List<Triple<String, String, String>> {
        val mappingsObj = config.optJSONObject("mappings")
        val mappingsArr = config.optJSONArray("mappings") ?: config.optJSONArray("extractions")
        val multiExtractions = mutableListOf<Triple<String, String, String>>()

        if (mappingsObj != null) {
            val keys = mappingsObj.keys()
            while (keys.hasNext()) {
                val outK = keys.next()
                val p = mappingsObj.optString(outK, "")
                if (outK.isNotBlank() && p.isNotBlank()) {
                    multiExtractions.add(Triple(p, outK, "AUTO"))
                }
            }
        } else if (mappingsArr != null) {
            for (i in 0 until mappingsArr.length()) {
                val item = mappingsArr.optJSONObject(i) ?: continue
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
        jsonRoot: Any,
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
        jsonRoot: Any,
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
        val config = try {
            JSONObject(node.configJson)
        } catch (_: JSONException) {
            JSONObject()
        }
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
            val jsonRoot: Any = when {
                trimmedInput.startsWith("[") -> JSONArray(trimmedInput)
                trimmedInput.startsWith("{") -> JSONObject(trimmedInput)
                else -> JSONObject(mapOf("value" to trimmedInput))
            }

            val multiExtractions = extractMultiMappings(config)
            if (multiExtractions.isNotEmpty()) {
                executeMulti(multiExtractions, jsonRoot, context, outputKey, defaultValue)
            } else {
                executeSingle(jsonRoot, jsonKey, targetType, defaultValue, outputKey, context)
            }
        } catch (e: JSONException) {
            NodeExecutionResult.Failed("JSON parsing error for '$jsonKey': ${e.message}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("JSON parsing error for '$jsonKey': ${e.message}")
        } catch (e: IllegalStateException) {
            NodeExecutionResult.Failed("JSON parsing error for '$jsonKey': ${e.message}")
        }
    }
}
