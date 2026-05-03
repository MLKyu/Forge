package com.mingeek.forge.data.discovery

/**
 * Bundled, hand-picked collections shipped with the app. Deliberately
 * conservative — each entry is a HuggingFace repo id we expect to exist
 * for the foreseeable future. Updates ship as a code change so we can
 * review against the rest of the catalog stack at the same time.
 *
 * To extend: add a new [Collection] entry below, or replace this source
 * entirely with a remote-feed implementation that satisfies
 * [CollectionSource].
 */
class StaticCollectionsSource : CollectionSource {

    override val sourceId: String = "static-collections"

    override suspend fun fetch(): List<Collection> = SEED_COLLECTIONS

    private companion object {
        val SEED_COLLECTIONS = listOf(
            Collection(
                id = "starter-tiny",
                title = "Starter — tiny",
                description = "Quantized 0.5B–1.5B models that run on almost any phone. " +
                    "Good first download for trying the app.",
                modelIds = listOf(
                    "unsloth/Qwen2.5-0.5B-Instruct-GGUF",
                    "unsloth/Llama-3.2-1B-Instruct-GGUF",
                    "unsloth/SmolLM2-1.7B-Instruct-GGUF",
                ),
                tags = setOf("starter", "tiny"),
            ),
            Collection(
                id = "coding",
                title = "Coding assistants",
                description = "Models tuned for writing and reviewing source code.",
                modelIds = listOf(
                    "unsloth/Qwen2.5-Coder-1.5B-Instruct-GGUF",
                    "unsloth/Qwen2.5-Coder-3B-Instruct-GGUF",
                    "bartowski/codegemma-2b-GGUF",
                ),
                tags = setOf("coding"),
            ),
            Collection(
                id = "general-3b",
                title = "General-purpose, 3B class",
                description = "Solid all-rounders that fit comfortably in 8GB phones.",
                modelIds = listOf(
                    "unsloth/Llama-3.2-3B-Instruct-GGUF",
                    "unsloth/Qwen2.5-3B-Instruct-GGUF",
                    "unsloth/Phi-3.5-mini-instruct-GGUF",
                ),
                tags = setOf("general"),
            ),
            Collection(
                id = "creative",
                title = "Creative writing",
                description = "Higher-temperature models known for narrative prose.",
                modelIds = listOf(
                    "unsloth/Mistral-Nemo-Instruct-2407-GGUF",
                    "unsloth/gemma-2-2b-it-GGUF",
                ),
                tags = setOf("creative"),
            ),
            Collection(
                id = "math",
                title = "Math + reasoning",
                description = "Models with strong chain-of-thought training.",
                modelIds = listOf(
                    "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
                    "unsloth/Qwen2.5-Math-1.5B-Instruct-GGUF",
                ),
                tags = setOf("math", "reasoning"),
            ),
        )
    }
}
