package com.mingeek.forge.di

import android.content.Context
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.core.hardware.DeviceProfiler
import com.mingeek.forge.data.catalog.huggingface.HfAuthInterceptor
import com.mingeek.forge.data.catalog.huggingface.HuggingFaceCatalogSource
import com.mingeek.forge.data.catalog.huggingface.HuggingFaceClient
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.data.agents.FileMemoryStore
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.HuggingFaceBlogSource
import com.mingeek.forge.data.discovery.HuggingFaceLikedSource
import com.mingeek.forge.data.discovery.HuggingFaceRecentSource
import com.mingeek.forge.data.discovery.HuggingFaceTrendingSource
import com.mingeek.forge.data.download.ModelDownloader
import com.mingeek.forge.data.storage.BenchmarkStore
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import java.io.File
import com.mingeek.forge.runtime.llamacpp.LlamaCppRuntime
import com.mingeek.forge.runtime.mediapipe.MediaPipeRuntime
import com.mingeek.forge.runtime.registry.BenchmarkRunner
import com.mingeek.forge.runtime.registry.RuntimeRegistry
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ForgeContainer(appContext: Context) {

    val settingsStore = SettingsStore(appContext)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .addInterceptor(HfAuthInterceptor { settingsStore.hfToken.value })
        .build()

    val huggingFaceApi = HuggingFaceClient.create(okHttpClient)

    val catalogSource = HuggingFaceCatalogSource(huggingFaceApi)

    val downloader = ModelDownloader(okHttpClient)

    val storage = ModelStorage(appContext)

    val benchmarkStore = BenchmarkStore(appContext)

    val deviceProfiler = DeviceProfiler(appContext)

    val deviceProfile = deviceProfiler.snapshot()

    val deviceFitScorer = DeviceFitScorer(deviceProfile)

    val runtimeRegistry = RuntimeRegistry(
        runtimes = listOf(
            LlamaCppRuntime(),
            MediaPipeRuntime(appContext),
        ),
    )

    val benchmarkRunner = BenchmarkRunner(
        registry = runtimeRegistry,
        onLoaded = { storage.markUsed(it) },
    )

    val discoveryRepository = DiscoveryRepository(
        sources = listOf(
            HuggingFaceTrendingSource(huggingFaceApi),
            HuggingFaceRecentSource(huggingFaceApi),
            HuggingFaceLikedSource(huggingFaceApi),
            HuggingFaceBlogSource(okHttpClient),
        ),
    )

    val agentRunHistory: MemoryStore = FileMemoryStore(
        File(appContext.filesDir, "agents/run_history.json"),
    )

    val chatHistory: MemoryStore = FileMemoryStore(
        File(appContext.filesDir, "chat/history.json"),
    )
}
