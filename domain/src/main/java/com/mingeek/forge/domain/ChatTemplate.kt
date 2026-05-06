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
            // Gemma's chat template doesn't define a system role. Earlier
            // versions of this code dropped SYSTEM turns silently, which
            // explained why agent system prompts had no effect on Gemma
            // models. Merge every SYSTEM turn (concatenated) into the
            // first user turn so the persona / instructions actually
            // reach the model.
            val systemBlock = turns
                .filter { it.role == Turn.Role.SYSTEM }
                .joinToString("\n\n") { it.content }
                .takeIf { it.isNotBlank() }
            val nonSystem = turns.filter { it.role != Turn.Role.SYSTEM }
            var injectedSystem = false
            for (t in nonSystem) {
                val tag = if (t.role == Turn.Role.USER) "user" else "model"
                append("<start_of_turn>$tag\n")
                if (!injectedSystem && t.role == Turn.Role.USER && systemBlock != null) {
                    append(systemBlock)
                    append("\n\n")
                    injectedSystem = true
                }
                append(t.content)
                append("<end_of_turn>\n")
            }
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

    /**
     * DeepSeek-R1 / V2 / V3 family (including the R1 distills onto Qwen and
     * Llama base models). Despite being distilled from Qwen, R1-Distill
     * keeps the upstream DeepSeek prompt format — NOT ChatML — so we must
     * route those models here even when the name happens to also include
     * "qwen" or "llama".
     *
     * Special-token strings use full-width pipes (｜ U+FF5C) and an
     * underscore-style middle-dot (▁ U+2581). Don't substitute ASCII pipes;
     * the tokenizer's BPE vocabulary keys exact bytes.
     *
     * Format produced (BOS is added by the tokenizer's addBos=true path so
     * we don't emit `<｜begin▁of▁sentence｜>` here):
     *
     * `{system?}<｜User｜>{u}<｜Assistant｜>{a}<｜end▁of▁sentence｜>...<｜Assistant｜>`
     */
    data object DeepSeek : ChatTemplate {
        override val id = "deepseek"
        override val stopSequences = listOf("<｜end▁of▁sentence｜>", "<｜User｜>")
        override fun format(turns: List<Turn>, addAssistantPrefix: Boolean): String = buildString {
            // Upstream Jinja merges system messages and emits them right
            // after BOS, before the first user turn — no separator. We
            // mirror that.
            val systemBlock = turns
                .filter { it.role == Turn.Role.SYSTEM }
                .joinToString("\n\n") { it.content }
                .takeIf { it.isNotBlank() }
            if (systemBlock != null) append(systemBlock)
            for (t in turns) {
                when (t.role) {
                    Turn.Role.SYSTEM -> Unit
                    Turn.Role.USER -> {
                        append("<｜User｜>")
                        append(t.content)
                    }
                    Turn.Role.ASSISTANT -> {
                        append("<｜Assistant｜>")
                        // R1 emits `<think>...</think>` reasoning ahead of
                        // the answer. Upstream's Jinja drops everything up
                        // to and including the last `</think>` when
                        // re-feeding the assistant turn — replicate so
                        // multi-turn conversations don't double-pay context
                        // for reasoning the model already discarded.
                        val visible = if ("</think>" in t.content)
                            t.content.substringAfterLast("</think>").trimStart()
                        else t.content
                        append(visible)
                        append("<｜end▁of▁sentence｜>")
                    }
                }
            }
            if (addAssistantPrefix) append("<｜Assistant｜>")
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
                // Modern DeepSeek family uses the `<｜User｜>` / `<｜Assistant｜>`
                // format — including R1 distills onto Qwen / Llama bases.
                // Match BEFORE the qwen / llama branches so distills route
                // here. Legacy `deepseek-coder` (pre-v2) stays on the
                // generic `deepseek` → ChatML branch below.
                "deepseek-r1" in haystack ||
                    "deepseek-v2" in haystack ||
                    "deepseek-v3" in haystack -> DeepSeek
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
                // DeepSeek Jinja embeds full-width-pipe role markers as
                // literal strings. Match BEFORE the ChatML check — some
                // hybrid templates mention both.
                "<｜User｜>" in chatTemplate || "<｜Assistant｜>" in chatTemplate -> DeepSeek
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
