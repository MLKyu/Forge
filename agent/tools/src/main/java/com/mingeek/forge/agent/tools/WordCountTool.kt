package com.mingeek.forge.agent.tools

import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.core.ToolResult

/**
 * Count chars / words / lines in a string.
 *
 * Arguments JSON: `{"text": "hello world"}`
 * Result JSON   : `{"chars": 11, "words": 2, "lines": 1}` or `{"error": "..."}`.
 *
 * Useful when the model needs to honour a length budget ("summarize in <100
 * words") or report stats on a passage. Keeping it as a tool rather than
 * relying on the model's own counting avoids the well-known counting failures
 * small LLMs exhibit on token-level tasks.
 */
class WordCountTool : Tool {

    override val name: String = "word_count"

    override val description: String =
        "Count chars, words, and lines in a piece of text. Use when the answer must respect a length budget or you need exact stats."

    override val argumentSchemaJson: String =
        """{"text": "string — the text to measure"}"""

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val text = extractStringField(argumentsJson, "text")
            ?: return ToolResult(
                outputJson = """{"error": "missing field: text"}""",
                isError = true,
            )
        // Words separated by any whitespace run; trim to avoid leading/trailing
        // empties skewing the count. Empty string deliberately yields 0 words.
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        // Count line *breaks* + 1 for non-empty input; empty string is 0 lines.
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        return ToolResult(
            outputJson = """{"chars": ${text.length}, "words": $words, "lines": $lines}""",
        )
    }
}
