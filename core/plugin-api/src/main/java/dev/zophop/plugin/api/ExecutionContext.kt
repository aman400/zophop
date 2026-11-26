package dev.zophop.plugin.api

import java.util.concurrent.ConcurrentHashMap

class ExecutionContext(
    initialVariables: Map<String, Any> = emptyMap(),
) {
    private val variables = ConcurrentHashMap<String, Any>(initialVariables)

    fun set(
        key: String,
        value: Any,
    ) {
        variables[key] = value
    }

    fun get(key: String): Any? = variables[key]

    fun getString(
        key: String,
        default: String = "",
    ): String = variables[key]?.toString() ?: default

    fun getAll(): Map<String, Any> = HashMap(variables)

    fun interpolate(template: String): String {
        if (template.isEmpty()) return ""
        return TEMPLATE_TOKEN_REGEX.replace(template) { match ->
            val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val value = variables[key]
            value?.toString() ?: match.value
        }
    }

    private companion object {
        val TEMPLATE_TOKEN_REGEX = Regex("\\$\\{([^{}]+)\\}|\\{([^{}]+)\\}")
    }
}
