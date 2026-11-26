package dev.zophop.domain.model

import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.longOrNull

val AppJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
        classDiscriminator = "type"
    }

fun parseJsonObjectSafe(jsonStr: String): JsonObject =
    try {
        if (jsonStr.isBlank()) {
            buildJsonObject {}
        } else {
            (AppJson.parseToJsonElement(jsonStr) as? JsonObject) ?: buildJsonObject {}
        }
    } catch (_: Exception) {
        buildJsonObject {}
    }

fun parseJsonArraySafe(jsonStr: String): JsonArray =
    try {
        if (jsonStr.isBlank()) {
            buildJsonArray {}
        } else {
            (AppJson.parseToJsonElement(jsonStr) as? JsonArray) ?: buildJsonArray {}
        }
    } catch (_: Exception) {
        buildJsonArray {}
    }

fun JsonElement.optString(default: String = ""): String =
    when (this) {
        is JsonNull -> default
        is JsonPrimitive -> content
        else -> toString()
    }

fun JsonObject.optString(key: String, default: String = ""): String =
    this[key]?.optString(default) ?: default

fun JsonObject.optInt(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: default

fun JsonObject.optLong(key: String, default: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.longOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: default

fun JsonObject.optDouble(key: String, default: Double = 0.0): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
        ?: default

fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        ?: default

fun JsonObject.optJsonObject(key: String): JsonObject? =
    this[key] as? JsonObject

fun JsonObject.optJsonArray(key: String): JsonArray? =
    this[key] as? JsonArray

fun JsonArray.optJsonObject(index: Int): JsonObject? =
    getOrNull(index) as? JsonObject

fun JsonArray.optString(index: Int, default: String = ""): String =
    getOrNull(index)?.optString(default) ?: default
