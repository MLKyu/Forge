package com.mingeek.forge.domain

sealed interface Source {
    val displayName: String

    data class HuggingFace(val repoId: String, val revision: String? = null) : Source {
        override val displayName: String = "HuggingFace"
    }

    data class Ollama(val name: String, val tag: String? = null) : Source {
        override val displayName: String = "Ollama"
    }

    data class QualcommAiHub(val modelId: String) : Source {
        override val displayName: String = "Qualcomm AI Hub"
    }

    data class MediaPipeGallery(val modelId: String) : Source {
        override val displayName: String = "MediaPipe Gallery"
    }

    data class MlcPrebuilt(val modelId: String) : Source {
        override val displayName: String = "MLC Prebuilts"
    }

    data class CustomUrl(val url: String) : Source {
        override val displayName: String = "Custom URL"
    }

    data object Local : Source {
        override val displayName: String = "Local"
    }
}
