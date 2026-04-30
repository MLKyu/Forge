package com.mingeek.forge.data.catalog.huggingface

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object HuggingFaceClient {
    const val BASE_URL = "https://huggingface.co/"

    fun create(httpClient: OkHttpClient): HuggingFaceApi {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(HuggingFaceApi::class.java)
    }

    fun fileResolveUrl(repoId: String, filename: String, revision: String = "main"): String =
        "${BASE_URL}$repoId/resolve/$revision/$filename"
}
