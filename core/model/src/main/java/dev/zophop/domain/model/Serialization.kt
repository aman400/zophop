package dev.zophop.domain.model

import kotlinx.serialization.json.Json

val AppJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
        classDiscriminator = "type"
    }
