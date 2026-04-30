package com.mingeek.forge.agent.core

import kotlinx.coroutines.flow.Flow

data class AgentInput(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
) {
    sealed interface Attachment {
        data class Text(val content: String) : Attachment
        data class FileRef(val path: String) : Attachment
    }
}

data class AgentContext(
    val agentId: String,
    val tokenBudget: Int = 8192,
    val maxToolCalls: Int = 8,
    val timeoutMillis: Long = 60_000,
    val memory: Map<String, String> = emptyMap(),
)

sealed interface AgentEvent {
    data class Token(val piece: String) : AgentEvent
    data class ToolCall(val name: String, val argumentsJson: String) : AgentEvent
    data class ToolResult(val name: String, val resultJson: String, val isError: Boolean) : AgentEvent
    data class Thought(val content: String) : AgentEvent
    data class Final(val output: String) : AgentEvent
    data class Failed(val message: String, val cause: Throwable? = null) : AgentEvent
}

interface Agent {
    val id: String
    val displayName: String

    fun run(input: AgentInput, ctx: AgentContext): Flow<AgentEvent>
}
