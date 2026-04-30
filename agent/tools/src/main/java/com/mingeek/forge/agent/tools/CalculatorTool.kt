package com.mingeek.forge.agent.tools

import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.core.ToolResult

/**
 * Evaluates a math expression. Supports + - * / and parentheses.
 *
 * Arguments JSON: `{"expression": "(2+3)*4"}`
 * Result JSON   : `{"result": 20.0}` or `{"error": "..."}`.
 */
class CalculatorTool : Tool {

    override val name: String = "calculator"

    override val description: String =
        "Evaluate a math expression with + - * / and parentheses. Use this when the answer requires arithmetic."

    override val argumentSchemaJson: String =
        """{"expression": "string, e.g. \"(2+3)*4\""}"""

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val expression = extractStringField(argumentsJson, "expression")
            ?: return ToolResult(
                outputJson = """{"error": "missing field: expression"}""",
                isError = true,
            )
        return try {
            val value = Calculator.evaluate(expression)
            val rendered = if (value == value.toLong().toDouble()) value.toLong().toString()
            else value.toString()
            ToolResult(outputJson = """{"result": $rendered}""")
        } catch (t: Throwable) {
            ToolResult(
                outputJson = """{"error": "${t.message?.replace("\"", "'") ?: "evaluation failed"}"}""",
                isError = true,
            )
        }
    }
}

/**
 * Pull a top-level string field out of a JSON object literal.
 * Naive — handles escaped quotes but not unicode escapes / nested braces in strings.
 * That's fine for tool-call arguments which are produced by the LLM and are intentionally simple.
 */
internal fun extractStringField(json: String, field: String): String? {
    val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    val match = pattern.find(json) ?: return null
    return match.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
}
