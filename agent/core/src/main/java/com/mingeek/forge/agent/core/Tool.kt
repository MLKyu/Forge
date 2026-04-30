package com.mingeek.forge.agent.core

interface Tool {
    val name: String
    val description: String
    val argumentSchemaJson: String

    suspend fun invoke(argumentsJson: String): ToolResult
}

data class ToolResult(
    val outputJson: String,
    val isError: Boolean = false,
)
