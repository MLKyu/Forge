package com.mingeek.forge.data.catalog.huggingface

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface HuggingFaceApi {
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") search: String?,
        @Query("filter") filter: String? = "gguf",
        @Query("sort") sort: String? = null,
        @Query("direction") direction: Int? = -1,
        @Query("limit") limit: Int = 30,
        @Query("full") full: Boolean = false,
    ): List<HfModelSummary>

    @GET("api/models/{repoId}")
    suspend fun modelDetail(
        @Path("repoId", encoded = true) repoId: String,
        @Query("blobs") blobs: Boolean = true,
    ): HfModelDetail
}
