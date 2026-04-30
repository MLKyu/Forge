package com.mingeek.forge.runtime.llamacpp

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LlamaCppRuntime : InferenceRuntime {

    override val id: RuntimeId = RuntimeId.LLAMA_CPP

    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.GGUF)

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsToolCalls = false,
        supportsVision = false,
        supportsKvCacheReuse = true,
        supportedAccelerators = setOf(RuntimeCapabilities.Accelerator.CPU),
    )

    private data class Session(
        val handle: ModelHandle,
        val modelPtr: Long,
        val ctxPtr: Long,
        val mutex: Mutex = Mutex(),
    )

    private val sessions = ConcurrentHashMap<SessionId, Session>()

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel = withContext(Dispatchers.IO) {
        require(model.format == ModelFormat.GGUF) { "llama.cpp only supports GGUF" }
        LlamaCppNative.nativeInit()

        val modelPtr = LlamaCppNative.nativeLoadModel(model.modelPath, config.gpuLayers)
        require(modelPtr != 0L) { "Failed to load model: ${model.modelPath}" }

        val threads = config.threads.takeIf { it > 0 }
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

        val ctxPtr = LlamaCppNative.nativeNewContext(
            modelPtr,
            nCtx = config.contextLength,
            nThreads = threads,
            nBatch = 512,
        )
        if (ctxPtr == 0L) {
            LlamaCppNative.nativeFreeModel(modelPtr)
            error("Failed to create llama context")
        }

        val sessionId = SessionId(UUID.randomUUID().toString())
        sessions[sessionId] = Session(model, modelPtr, ctxPtr)

        val chatTemplate = LlamaCppNative.nativeGetMetadataString(modelPtr, "tokenizer.chat_template")

        LoadedModel(
            sessionId = sessionId,
            handle = model,
            runtimeId = id,
            capabilities = capabilities,
            chatTemplate = chatTemplate,
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = flow {
        val s = sessions[session] ?: error("Session not loaded: $session")

        s.mutex.withLock {
            val text = buildString {
                prompt.systemPrompt?.let { append(it); append("\n\n") }
                append(prompt.text)
            }

            val tokens = LlamaCppNative.nativeTokenize(
                s.modelPtr, text,
                addBos = true,
                parseSpecial = true,
            ) ?: error("tokenize failed")

            LlamaCppNative.nativeResetContext(s.ctxPtr)

            if (!LlamaCppNative.nativeDecodeTokens(s.ctxPtr, tokens)) {
                emit(Token.Done(Token.FinishReason.ERROR, Token.TokenUsage(tokens.size, 0, tokens.size)))
                return@flow
            }

            val sampler = LlamaCppNative.nativeNewSampler(
                temperature = prompt.temperature,
                topP = prompt.topP,
                topK = 40,
                seed = 0L,
            )

            var completion = 0
            try {
                val promptCount = tokens.size
                var stopHit = false
                val emittedBuffer = StringBuilder()

                for (i in 0 until prompt.maxTokens) {
                    val tokenId = LlamaCppNative.nativeSampleNext(s.ctxPtr, sampler)
                    if (tokenId < 0) {
                        emit(Token.Done(Token.FinishReason.ERROR, usage(promptCount, completion)))
                        return@flow
                    }
                    if (LlamaCppNative.nativeIsEog(s.modelPtr, tokenId)) {
                        emit(Token.Done(Token.FinishReason.STOP, usage(promptCount, completion)))
                        return@flow
                    }

                    val piece = LlamaCppNative.nativeTokenToPiece(s.modelPtr, tokenId)
                    if (piece.isNotEmpty()) {
                        emit(Token.Text(piece))
                        completion++

                        emittedBuffer.append(piece)
                        for (stop in prompt.stopSequences) {
                            if (stop.isNotEmpty() && emittedBuffer.endsWith(stop)) {
                                emit(Token.Done(Token.FinishReason.STOP_SEQUENCE, usage(promptCount, completion)))
                                stopHit = true
                                break
                            }
                        }
                        if (stopHit) return@flow
                        if (emittedBuffer.length > 256) {
                            emittedBuffer.delete(0, emittedBuffer.length - 256)
                        }
                    }

                    if (!LlamaCppNative.nativeDecodeToken(s.ctxPtr, tokenId)) {
                        emit(Token.Done(Token.FinishReason.ERROR, usage(promptCount, completion)))
                        return@flow
                    }
                }
                emit(Token.Done(Token.FinishReason.MAX_TOKENS, usage(promptCount, completion)))
            } finally {
                LlamaCppNative.nativeFreeSampler(sampler)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun unload(session: SessionId): Unit = withContext(Dispatchers.IO) {
        val s = sessions.remove(session) ?: return@withContext
        LlamaCppNative.nativeFreeContext(s.ctxPtr)
        LlamaCppNative.nativeFreeModel(s.modelPtr)
    }

    private fun usage(promptTokens: Int, completionTokens: Int) = Token.TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
    )
}
