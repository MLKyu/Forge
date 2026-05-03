package com.mingeek.forge.di

import android.content.Context
import com.mingeek.forge.core.hardware.DeviceFitScorer
import com.mingeek.forge.core.hardware.DeviceProfiler
import com.mingeek.forge.data.catalog.huggingface.HfAuthInterceptor
import com.mingeek.forge.data.catalog.huggingface.HuggingFaceCatalogSource
import com.mingeek.forge.data.catalog.huggingface.HuggingFaceClient
import com.mingeek.forge.agent.memory.MemoryStore
import com.mingeek.forge.data.agents.FileMemoryStore
import com.mingeek.forge.feature.agents.LlmAgent
import com.mingeek.forge.feature.discover.CuratorAgentFactory
import com.mingeek.forge.data.discovery.DiscoveryNotifier
import com.mingeek.forge.data.discovery.DiscoveryRepository
import com.mingeek.forge.data.discovery.HuggingFaceBlogSource
import com.mingeek.forge.data.discovery.HuggingFaceLikedSource
import com.mingeek.forge.data.discovery.HuggingFaceRecentSource
import com.mingeek.forge.data.discovery.HuggingFaceTrendingSource
import com.mingeek.forge.data.discovery.RedditLocalLlamaSource
import com.mingeek.forge.data.download.ModelDownloader
import com.mingeek.forge.data.storage.BenchmarkStore
import com.mingeek.forge.data.storage.ModelStorage
import com.mingeek.forge.data.storage.SettingsStore
import java.io.File
import com.mingeek.forge.runtime.executorch.ExecuTorchRuntime
import com.mingeek.forge.runtime.llamacpp.LlamaCppRuntime
import com.mingeek.forge.runtime.mediapipe.MediaPipeRuntime
import com.mingeek.forge.runtime.mlc.MlcLlmRuntime
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
            // ExecuTorch + MLC stubs — registered so the registry can route
            // .pte / MLC formats to them; load() throws until native bindings
            // are wired in (PLANNING §11 Phase 2/4/5).
            ExecuTorchRuntime(ExecuTorchRuntime.Variant.QNN),
            ExecuTorchRuntime(ExecuTorchRuntime.Variant.EXYNOS),
            ExecuTorchRuntime(ExecuTorchRuntime.Variant.CPU),
            MlcLlmRuntime(),
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
            RedditLocalLlamaSource(okHttpClient),
        ),
    )

    val agentRunHistory: MemoryStore = FileMemoryStore(
        File(appContext.filesDir, "agents/run_history.json"),
    )

    val chatHistory: MemoryStore = FileMemoryStore(
        File(appContext.filesDir, "chat/history.json"),
    )

    val discoveryNotifier = DiscoveryNotifier(appContext)

    /**
     * Builds an LlmAgent against an installed model so the Curator (and any
     * other feature that wants a one-shot evaluator) can run without
     * cross-module knowledge of LlmAgent. Tools intentionally empty — curator
     * prompts are short, single-turn, and tool calls would confuse the parser.
     */
    val curatorAgentFactory: CuratorAgentFactory = { model ->
        LlmAgent(
            id = "curator-${model.id}",
            displayName = model.displayName,
            model = model,
            registry = runtimeRegistry,
            systemPrompt = "You are a concise model evaluator. Reply with one SCORE line as instructed.",
            maxTokens = 96,
            temperature = 0.2f,
            tools = emptyList(),
        )
    }
}
