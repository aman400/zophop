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
        var result = template
        for ((key, value) in variables) {
            val placeholder1 = "\${$key}"
            val placeholder2 = "{$key}"
            val valStr = value.toString()
            result = result.replace(placeholder1, valStr).replace(placeholder2, valStr)
        }
        return result
    }
}
