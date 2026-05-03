package com.mingeek.forge.data.catalog.huggingface

import com.mingeek.forge.data.catalog.ModelCardDetail
import com.mingeek.forge.data.catalog.ModelCatalogSource
import com.mingeek.forge.data.catalog.RemoteFile
import com.mingeek.forge.data.catalog.SearchQuery
import com.mingeek.forge.domain.Capability
import com.mingeek.forge.domain.License
import com.mingeek.forge.domain.ModelCard
import com.mingeek.forge.domain.ModelFamily
import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.Quant
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.domain.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HuggingFaceCatalogSource(
    private val api: HuggingFaceApi,
    private val tags: List<String> = listOf("gguf", "mediapipe"),
) : ModelCatalogSource {

    override val sourceId: String = "huggingface"

    override fun search(query: SearchQuery): Flow<List<ModelCard>> = flow {
        val sortKey = when (query.sort) {
            SearchQuery.Sort.DOWNLOADS -> "downloads"
            SearchQuery.Sort.RECENT -> "lastModified"
            SearchQuery.Sort.RELEVANCE, SearchQuery.Sort.SIZE_ASC -> null
        }
        val merged = coroutineScope {
            tags.map { tag ->
                async { api.searchModels(search = query.text, filter = tag, sort = sortKey) }
            }.flatMap { it.await() }
        }
        emit(merged.distinctBy { it.id }.map { it.toCardSummary() })
    }

    override suspend fun details(id: String): ModelCardDetail {
        val detail = api.modelDetail(id)
        val readme = (detail.cardData?.get("model_summary") as? String)
            ?: detail.cardData?.get("description") as? String

        // cardData["license"] is the per-card-yaml license declaration; when
        // present it's authoritative over the tag-based inference because
        // model authors set it explicitly. Fall back to tag inference.
        val cardLicense = (detail.cardData?.get("license") as? String)
            ?.let { com.mingeek.forge.domain.Licenses.fromSpdx(it) }
        val effectiveLicense = cardLicense ?: inferLicense(detail.tags)

        val variants = mutableListOf<ModelCard>()
        val files = mutableListOf<RemoteFile>()

        for (file in detail.siblings) {
            val format = when {
                file.rfilename.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
                file.rfilename.endsWith(".task", ignoreCase = true) -> ModelFormat.MEDIAPIPE_TASK
                else -> continue
            }
            val q = parseQuant(file.rfilename)
            val runtime = when (format) {
                ModelFormat.GGUF -> RuntimeId.LLAMA_CPP
                ModelFormat.MEDIAPIPE_TASK -> RuntimeId.MEDIAPIPE
                else -> RuntimeId.LLAMA_CPP
            }
            variants += ModelCard(
                id = "${detail.id}::${file.rfilename}",
                displayName = "${detail.id} (${q.name})",
                family = inferFamily(detail.id, detail.tags),
                sizeBytes = file.lfs?.size ?: file.size ?: 0L,
                quantization = q,
                format = format,
                contextLength = 4096,
                capabilities = setOf(Capability.CHAT, Capability.INSTRUCT),
                license = effectiveLicense,
                source = Source.HuggingFace(detail.id),
                recommendedRuntimes = listOf(runtime),
            )
            files += RemoteFile(
                name = file.rfilename,
                url = HuggingFaceClient.fileResolveUrl(detail.id, file.rfilename),
                sizeBytes = file.lfs?.size ?: file.size ?: 0L,
                sha256 = file.lfs?.sha256,
            )
        }

        val baseCard = variants.firstOrNull() ?: ModelCard(
            id = detail.id,
            displayName = detail.id,
            family = inferFamily(detail.id, detail.tags),
            sizeBytes = 0L,
            quantization = Quant.UNKNOWN,
            format = ModelFormat.GGUF,
            contextLength = 4096,
            capabilities = setOf(Capability.CHAT),
            license = effectiveLicense,
            source = Source.HuggingFace(detail.id),
            recommendedRuntimes = listOf(RuntimeId.LLAMA_CPP),
        )

        return ModelCardDetail(
            card = baseCard,
            description = readme ?: "",
            readmeMarkdown = null,
            variants = variants,
            files = files,
        )
    }

    private fun HfModelSummary.toCardSummary(): ModelCard {
        val format = when {
            "mediapipe" in tags -> ModelFormat.MEDIAPIPE_TASK
            "gguf" in tags -> ModelFormat.GGUF
            else -> ModelFormat.GGUF
        }
        val runtime = when (format) {
            ModelFormat.MEDIAPIPE_TASK -> RuntimeId.MEDIAPIPE
            else -> RuntimeId.LLAMA_CPP
        }
        return ModelCard(
            id = id,
            displayName = id,
            family = inferFamily(id, tags),
            sizeBytes = 0L,
            quantization = Quant.UNKNOWN,
            format = format,
            contextLength = 4096,
            capabilities = setOf(Capability.CHAT),
            license = inferLicense(tags),
            source = Source.HuggingFace(id),
            recommendedRuntimes = listOf(runtime),
        )
    }

    private fun parseQuant(filename: String): Quant {
        val upper = filename.uppercase()
        return when {
            "Q4_K_M" in upper -> Quant.Q4_K_M
            "Q4_0" in upper -> Quant.Q4_0
            "Q5_K_M" in upper -> Quant.Q5_K_M
            "Q6_K" in upper -> Quant.Q6_K
            "Q8_0" in upper -> Quant.Q8_0
            "F16" in upper || "FP16" in upper -> Quant.F16
            "BF16" in upper -> Quant.BF16
            "F32" in upper -> Quant.F32
            "INT8" in upper -> Quant.INT8
            "INT4" in upper -> Quant.INT4
            else -> Quant.UNKNOWN
        }
    }

    private fun inferFamily(repoId: String, tags: List<String>): ModelFamily {
        val name = repoId.substringAfter('/')
        val vendor = repoId.substringBefore('/').takeIf { it.contains('/').not() && repoId.contains('/') }
        val parameterB = tags.firstNotNullOfOrNull { tag ->
            Regex("([0-9.]+)[bB]\\b").find(tag)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("([0-9.]+)[bB]\\b").find(name)?.groupValues?.get(1)?.toFloatOrNull()
        }
        return ModelFamily(name = name, vendor = vendor, parameterBillions = parameterB)
    }

    private fun inferLicense(tags: List<String>): License = com.mingeek.forge.domain.Licenses.fromTags(tags)
}
