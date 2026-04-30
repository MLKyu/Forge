package com.mingeek.forge.agent.tools

import com.mingeek.forge.agent.core.Tool
import com.mingeek.forge.agent.core.ToolResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Returns the current local time as ISO-8601 (with timezone offset).
 *
 * Arguments JSON: `{}` (no parameters)
 * Result JSON   : `{"now": "2026-04-30T14:32:18+09:00", "epoch_ms": 1746016338000}`
 */
class CurrentTimeTool : Tool {

    override val name: String = "current_time"

    override val description: String =
        "Get the current local time as an ISO-8601 timestamp. Use this when the user asks about the current time, date, or relative time."

    override val argumentSchemaJson: String = """{}"""

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val now = Instant.now()
        val iso = ISO_OFFSET.format(now.atZone(ZoneId.systemDefault()))
        return ToolResult(
            outputJson = """{"now": "$iso", "epoch_ms": ${now.toEpochMilli()}}""",
        )
    }

    private companion object {
        val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
