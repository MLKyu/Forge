package com.mingeek.forge.data.catalog

import com.mingeek.forge.domain.Capability
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.Quant
import kotlinx.coroutines.flow.Flow

data class SearchQuery(
    val text: String? = null,
    val formats: Set<ModelFormat> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val maxSizeBytes: Long? = null,
    val quantizations: Set<Quant> = emptySet(),
    val sort: Sort = Sort.RELEVANCE,
) {
    enum class Sort { RELEVANCE, DOWNLOADS, RECENT, SIZE_ASC }
}

data class ModelCardDetail(
    val card: ModelCard,
    val description: String,
    val readmeMarkdown: String?,
    val variants: List<ModelCard>,
    val files: List<RemoteFile>,
)

data class RemoteFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
)

interface ModelCatalogSource {
    val sourceId: String

    fun search(query: SearchQuery): Flow<List<ModelCard>>

    suspend fun details(id: String): ModelCardDetail
}
