package com.mingeek.forge.runtime.executorch

import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.InferenceRuntime
import com.mingeek.forge.runtime.core.LoadConfig
import com.mingeek.forge.runtime.core.LoadedModel
import com.mingeek.forge.runtime.core.ModelHandle
import com.mingeek.forge.runtime.core.Prompt
import com.mingeek.forge.runtime.core.RuntimeCapabilities
import com.mingeek.forge.runtime.core.SessionId
import com.mingeek.forge.runtime.core.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ExecuTorch-backed runtime — three variants (QNN / Exynos NPU / CPU)
 * share the same Kotlin class because the underlying [LlmModule] picks
 * its delegate based on what's compiled into the .pte file. The variant
 * id is informational so [RuntimeRegistry] can advertise the right
 * accelerator and the catalog can score fit per variant.
 *
 * Tokenizer requirement: ExecuTorch's LlmModule needs a tokenizer file
 * path passed at construction time. Pass it via
 * [LoadConfig.extras]"executorch_tokenizer_path"; default is the .pte
 * file's parent dir + "tokenizer.bin" which matches the convention in
 * pytorch/executorch examples.
 */
class ExecuTorchRuntime(
    private val variant: Variant,
) : InferenceRuntime {

    enum class Variant {
        QNN,
        EXYNOS,
        CPU,
    }

    override val id: RuntimeId = when (variant) {
        Variant.QNN -> RuntimeId.EXECUTORCH_QNN
        Variant.EXYNOS -> RuntimeId.EXECUTORCH_EXYNOS
        Variant.CPU -> RuntimeId.EXECUTORCH_CPU
    }

    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.EXECUTORCH_PTE)

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsToolCalls = false,
        supportsVision = false,
        supportsKvCacheReuse = true,
        supportedAccelerators = setOf(
            when (variant) {
                Variant.QNN -> RuntimeCapabilities.Accelerator.NPU_QNN
                Variant.EXYNOS -> RuntimeCapabilities.Accelerator.NPU_EXYNOS
                Variant.CPU -> RuntimeCapabilities.Accelerator.CPU
            },
        ),
    )

    private data class Session(
        val handle: ModelHandle,
        val module: LlmModule,
        val mutex: Mutex = Mutex(),
    )

    private val sessions = ConcurrentHashMap<SessionId, Session>()

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel = withContext(Dispatchers.IO) {
        require(model.format == ModelFormat.EXECUTORCH_PTE) { "ExecuTorch only supports .pte" }

        val tokenizerPath = config.extras[TOKENIZER_PATH_KEY]
            ?: defaultTokenizerPath(model.modelPath)
            ?: error(
                "ExecuTorch: no tokenizer specified. Pass LoadConfig.extras[\"$TOKENIZER_PATH_KEY\"] " +
                    "or place tokenizer.bin alongside the .pte file.",
            )

        val module = LlmModule(model.modelPath, tokenizerPath, /* temperature = */ 0.0f)
        val rc = module.load()
        if (rc != 0) {
            module.resetNative()
            error("ExecuTorch load failed (rc=$rc) for ${model.modelPath}")
        }
        val sessionId = SessionId(UUID.randomUUID().toString())
        sessions[sessionId] = Session(model, module)
        LoadedModel(
            sessionId = sessionId,
            handle = model,
            runtimeId = id,
            capabilities = capabilities,
            chatTemplate = null,
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = flow {
        val s = sessions[session] ?: error("Unknown ExecuTorch session: $session")
        s.mutex.withLock {
            val channel = Channel<Token>(capacity = Channel.UNLIMITED)
            // ExecuTorch fires the callback on its own thread; we marshal
            // pieces back onto the coroutine via an unlimited channel and
            // then drain in order.
            val callback = object : LlmCallback {
                override fun onResult(result: String) {
                    runBlocking { channel.send(Token.Text(result)) }
                }
                override fun onStats(tokensPerSecond: Float) {
                    // Dropped; surfaced via Token.Done.usage if we ever
                    // start tracking TTFT here.
                }
            }
            // Run generation on a parallel coroutine so the channel can
            // drain while the native code is still emitting.
            kotlinx.coroutines.coroutineScope {
                launch(Dispatchers.IO) {
                    runCatching {
                        s.module.generate(prompt.text, prompt.maxTokens, callback, /* echo = */ false)
                    }
                    channel.close()
                }
                for (token in channel) emit(token)
            }
            emit(
                Token.Done(
                    finishReason = Token.FinishReason.STOP,
                    usage = Token.TokenUsage(0, 0, 0),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun unload(session: SessionId) {
        withContext(Dispatchers.IO) {
            val s = sessions.remove(session) ?: return@withContext
            runCatching { s.module.stop() }
            runCatching { s.module.resetNative() }
        }
    }

    private fun defaultTokenizerPath(pteFilePath: String): String? {
        val sibling = File(pteFilePath).parentFile?.let { File(it, "tokenizer.bin") }
        return sibling?.takeIf { it.exists() }?.absolutePath
    }

    private companion object {
        const val TOKENIZER_PATH_KEY = "executorch_tokenizer_path"
    }
}
