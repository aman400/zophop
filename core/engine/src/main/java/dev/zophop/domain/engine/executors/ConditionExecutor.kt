package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import java.util.regex.Pattern

/**
 * Strategy executor for [NodeType.CONDITION].
 * Evaluates variable values against operators (`EQUALS`, `NOT_EQUALS`, `CONTAINS`, `NOT_CONTAINS`, `REGEX_MATCH`, `NOT_EMPTY`, `IS_EMPTY`, `GREATER_THAN`, `LESS_THAN`).
 * Returns [NodeExecutionResult.Success] with outputPort `"true"` or `"false"`.
 */
class ConditionExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseJsonObjectSafe(node.configJson)
        val variableName = config.optString("variable", "code").ifEmpty { config.optString("variable", "body") }.trim()
        val operator = config.optString("operator", "NOT_EMPTY").trim()
        val targetValue = context.interpolate(config.optString("value", "")).trim()

        val actualValue = context.getString(variableName, "")
        val conditionMet = evaluateCondition(operator, actualValue, targetValue)

        val targetStr = if (targetValue.isNotBlank()) " '$targetValue'" else ""
        val details =
            buildString {
                appendLine("Variable: $variableName")
                appendLine("Actual Value: '${ExecutionLogFormatter.previewText(actualValue, 250)}'")
                appendLine("Operator: $operator")
                if (targetValue.isNotBlank()) {
                    appendLine("Target Value: '$targetValue'")
                }
                append("Output Port: ${if (conditionMet) "true" else "false"}")
            }

        return if (conditionMet) {
            val summary = "Condition '$variableName $operator$targetStr' passed (TRUE)"
            NodeExecutionResult.Success(
                outputPort = "true",
                logMessage = ExecutionLogFormatter.buildStructuredLog(summary, listOf("Evaluation Details" to details)),
            )
        } else {
            val summary = "Condition '$variableName $operator$targetStr' evaluated to FALSE (Actual: '$actualValue')"
            NodeExecutionResult.Success(
                outputPort = "false",
                logMessage = ExecutionLogFormatter.buildStructuredLog(summary, listOf("Evaluation Details" to details)),
            )
        }
    }

    private fun evaluateCondition(
        operator: String,
        actualValue: String,
        targetValue: String,
    ): Boolean =
        when (operator.uppercase()) {
            "EQUALS", "==" -> actualValue.equals(targetValue, ignoreCase = true)
            "NOT_EQUALS", "!=" -> !actualValue.equals(targetValue, ignoreCase = true)
            "CONTAINS" -> actualValue.contains(targetValue, ignoreCase = true)
            "NOT_CONTAINS" -> !actualValue.contains(targetValue, ignoreCase = true)
            "STARTS_WITH" -> actualValue.startsWith(targetValue, ignoreCase = true)
            "ENDS_WITH" -> actualValue.endsWith(targetValue, ignoreCase = true)
            "REGEX_MATCH" ->
                try {
                    Pattern.compile(targetValue, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(actualValue).find()
                } catch (_: Exception) {
                    false
                }
            "NOT_REGEX_MATCH", "!REGEX_MATCH" ->
                try {
                    !Pattern.compile(targetValue, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(actualValue).find()
                } catch (_: Exception) {
                    false
                }
            "NOT_EMPTY" -> actualValue.isNotBlank()
            "IS_EMPTY" -> actualValue.isBlank()
            "GREATER_THAN", "GT", ">" -> compareNumbers(actualValue, targetValue) { a, b -> a > b }
            "GREATER_THAN_OR_EQUAL", "GTE", ">=" -> compareNumbers(actualValue, targetValue) { a, b -> a >= b }
            "LESS_THAN", "LT", "<" -> compareNumbers(actualValue, targetValue) { a, b -> a < b }
            "LESS_THAN_OR_EQUAL", "LTE", "<=" -> compareNumbers(actualValue, targetValue) { a, b -> a <= b }
            else -> actualValue.isNotBlank()
        }

    private inline fun compareNumbers(
        actualValue: String,
        targetValue: String,
        predicate: (Double, Double) -> Boolean,
    ): Boolean {
        val actNum = actualValue.toDoubleOrNull()
        val tgtNum = targetValue.toDoubleOrNull()
        return actNum != null && tgtNum != null && predicate(actNum, tgtNum)
    }
}
