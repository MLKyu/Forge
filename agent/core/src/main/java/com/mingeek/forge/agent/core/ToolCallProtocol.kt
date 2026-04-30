package com.mingeek.forge.agent.core

/**
 * Defines the prompt format an LLM should use to invoke a tool.
 *
 * We use a deliberately simple line protocol instead of free-form JSON so that
 * smaller models (1–3 B) can stay on rails — they only need to emit two lines:
 *
 * ```
 * TOOL: calculator
 * ARGS: {"expression": "2+2"}
 * ```
 *
 * The tool runner detects this prefix at the start of the assistant message,
 * dispatches the call, and feeds the result back as a `TOOL_RESULT:` turn.
 * If the model writes a normal sentence instead, that's the final answer.
 */
object ToolCallProtocol {

    /** Build a system-prompt prelude listing every available tool. */
    fun buildPrelude(tools: List<Tool>): String {
        if (tools.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("You have access to the following tools.\n\n")
        for (t in tools) {
            sb.append("- ").append(t.name).append(": ").append(t.description).append("\n")
            sb.append("  arguments: ").append(t.argumentSchemaJson).append("\n")
        }
        sb.append(
            """

When (and only when) you need to use a tool, respond with EXACTLY two lines and nothing else:
TOOL: <tool name>
ARGS: <JSON object>

Wait for a TOOL_RESULT message before giving your final answer.
If you do not need a tool, answer the user normally without the TOOL: prefix.

""",
        )
        return sb.toString()
    }

    /**
     * Try to parse a tool call out of the assistant's most recent output.
     * Returns null if the output does not start with `TOOL:`.
     */
    fun parseCall(assistantOutput: String): ToolCall? {
        val trimmed = assistantOutput.trimStart()
        if (!trimmed.startsWith("TOOL:")) return null

        val lines = trimmed.lineSequence().iterator()
        val toolLine = if (lines.hasNext()) lines.next() else return null
        val argsLine = if (lines.hasNext()) lines.next() else return null

        val name = toolLine.removePrefix("TOOL:").trim()
        val args = argsLine.removePrefix("ARGS:").trim()
        if (name.isEmpty() || !args.startsWith("{")) return null
        return ToolCall(name = name, argumentsJson = args)
    }

    /** Format a tool result so the LLM can read it on the next turn. */
    fun renderResult(result: ToolResult): String =
        if (result.isError) "TOOL_RESULT (error): ${result.outputJson}"
        else "TOOL_RESULT: ${result.outputJson}"

    data class ToolCall(val name: String, val argumentsJson: String)
}
