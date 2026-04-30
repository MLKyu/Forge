package com.mingeek.forge.domain

sealed interface ChatTemplate {

    val id: String
    val stopSequences: List<String>

    fun format(turns: List<Turn>, addAssistantPrefix: Boolean = true): String

    data class Turn(val role: Role, val content: String) {
        enum class Role { SYSTEM, USER, ASSISTANT }
    }

    data object ChatML : ChatTemplate {
        override val id = "chatml"
        override val stopSequences = listOf("<|im_end|>", "<|im_start|>")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            for (t in turns) {
                val tag = when (t.role) {
                    Turn.Role.SYSTEM -> "system"
                    Turn.Role.USER -> "user"
                    Turn.Role.ASSISTANT -> "assistant"
                }
                append("<|im_start|>$tag\n")
                append(t.content)
                append("<|im_end|>\n")
            }
            if (addAssistantPrefix) append("<|im_start|>assistant\n")
        }
    }

    data object Llama3 : ChatTemplate {
        override val id = "llama-3"
        override val stopSequences = listOf("<|eot_id|>", "<|end_of_text|>")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            append("<|begin_of_text|>")
            for (t in turns) {
                val tag = when (t.role) {
                    Turn.Role.SYSTEM -> "system"
                    Turn.Role.USER -> "user"
                    Turn.Role.ASSISTANT -> "assistant"
                }
                append("<|start_header_id|>$tag<|end_header_id|>\n\n")
                append(t.content)
                append("<|eot_id|>")
            }
            if (addAssistantPrefix) append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }

    data object Gemma : ChatTemplate {
        override val id = "gemma"
        override val stopSequences = listOf("<end_of_turn>")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            for (t in turns) {
                if (t.role == Turn.Role.SYSTEM) {
                    // Gemma has no system role; prepend to first user turn
                    continue
                }
                val tag = if (t.role == Turn.Role.USER) "user" else "model"
                append("<start_of_turn>$tag\n")
                append(t.content)
                append("<end_of_turn>\n")
            }
            // Inject system as a prefix on the first user turn (simple approach)
            if (addAssistantPrefix) append("<start_of_turn>model\n")
        }
    }

    data object Phi3 : ChatTemplate {
        override val id = "phi-3"
        override val stopSequences = listOf("<|end|>", "<|user|>", "<|system|>")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            for (t in turns) {
                val tag = when (t.role) {
                    Turn.Role.SYSTEM -> "system"
                    Turn.Role.USER -> "user"
                    Turn.Role.ASSISTANT -> "assistant"
                }
                append("<|$tag|>\n")
                append(t.content)
                append("<|end|>\n")
            }
            if (addAssistantPrefix) append("<|assistant|>\n")
        }
    }

    data object MistralInstruct : ChatTemplate {
        override val id = "mistral-instruct"
        override val stopSequences = listOf("</s>", "[INST]")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            // System merged into first user turn with <<SYS>>
            val sys = turns.firstOrNull { it.role == Turn.Role.SYSTEM }?.content
            val nonSystem = turns.filter { it.role != Turn.Role.SYSTEM }
            for ((index, t) in nonSystem.withIndex()) {
                when (t.role) {
                    Turn.Role.USER -> {
                        append("[INST] ")
                        if (index == 0 && sys != null) {
                            append("<<SYS>>\n")
                            append(sys)
                            append("\n<</SYS>>\n\n")
                        }
                        append(t.content)
                        append(" [/INST]")
                    }
                    Turn.Role.ASSISTANT -> {
                        append(" ")
                        append(t.content)
                        append("</s>")
                    }
                    Turn.Role.SYSTEM -> { /* unreachable */ }
                }
            }
            // Assistant prefix is implicit after [/INST] — no extra marker
        }
    }

    data object Plain : ChatTemplate {
        override val id = "plain"
        override val stopSequences = listOf("\nUser:", "\nuser:")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            for (t in turns) {
                val tag = when (t.role) {
                    Turn.Role.SYSTEM -> "System: "
                    Turn.Role.USER -> "User: "
                    Turn.Role.ASSISTANT -> "Assistant: "
                }
                append(tag)
                append(t.content)
                append('\n')
            }
            if (addAssistantPrefix) append("Assistant: ")
        }
    }

    companion object {
        fun detect(modelId: String, fileName: String? = null): ChatTemplate {
            val haystack = (modelId + " " + (fileName ?: "")).lowercase()
            return when {
                "llama-3" in haystack || "llama3" in haystack -> Llama3
                "gemma" in haystack -> Gemma
                "phi-3" in haystack || "phi3" in haystack || "phi-4" in haystack -> Phi3
                "qwen" in haystack || "hermes" in haystack || "openhermes" in haystack ||
                    "yi-" in haystack || "deepseek" in haystack || "smol" in haystack -> ChatML
                "mistral" in haystack || "mixtral" in haystack || "zephyr" in haystack -> MistralInstruct
                else -> Plain
            }
        }

        fun fromChatTemplateString(chatTemplate: String): ChatTemplate? {
            if (chatTemplate.isBlank()) return null
            return when {
                "<|im_start|>" in chatTemplate -> ChatML
                "<|begin_of_text|>" in chatTemplate || "<|start_header_id|>" in chatTemplate -> Llama3
                "<start_of_turn>" in chatTemplate -> Gemma
                "<|user|>" in chatTemplate && "<|assistant|>" in chatTemplate && "<|end|>" in chatTemplate -> Phi3
                "[INST]" in chatTemplate -> MistralInstruct
                else -> null
            }
        }
    }
}
