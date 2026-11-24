package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import java.util.regex.Pattern

/**
 * Strategy executor for [NodeType.CONDITION].
 * Evaluates variable values against operators (`EQUALS`, `NOT_EQUALS`, `CONTAINS`, `NOT_CONTAINS`, `REGEX_MATCH`, `NOT_EMPTY`, `IS_EMPTY`, `GREATER_THAN`, `LESS_THAN`).
 * Returns [NodeExecutionResult.Success] on pass or [NodeExecutionResult.Failed] on failure.
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

        val conditionMet =
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
                "GREATER_THAN", "GT", ">" -> {
                    val actNum = actualValue.toDoubleOrNull()
                    val tgtNum = targetValue.toDoubleOrNull()
                    actNum != null && tgtNum != null && actNum > tgtNum
                }
                "GREATER_THAN_OR_EQUAL", "GTE", ">=" -> {
                    val actNum = actualValue.toDoubleOrNull()
                    val tgtNum = targetValue.toDoubleOrNull()
                    actNum != null && tgtNum != null && actNum >= tgtNum
                }
                "LESS_THAN", "LT", "<" -> {
                    val actNum = actualValue.toDoubleOrNull()
                    val tgtNum = targetValue.toDoubleOrNull()
                    actNum != null && tgtNum != null && actNum < tgtNum
                }
                "LESS_THAN_OR_EQUAL", "LTE", "<=" -> {
                    val actNum = actualValue.toDoubleOrNull()
                    val tgtNum = targetValue.toDoubleOrNull()
                    actNum != null && tgtNum != null && actNum <= tgtNum
                }
                else -> actualValue.isNotBlank()
            }

        val targetStr = if (targetValue.isNotBlank()) " '$targetValue'" else ""
        return if (conditionMet) {
            NodeExecutionResult.Success(outputPort = "true", logMessage = "Condition '$variableName $operator$targetStr' passed (TRUE)")
        } else {
            NodeExecutionResult.Success(
                outputPort = "false",
                logMessage = "Condition '$variableName $operator$targetStr' evaluated to FALSE (Actual: '$actualValue')",
            )
        }
    }
}
