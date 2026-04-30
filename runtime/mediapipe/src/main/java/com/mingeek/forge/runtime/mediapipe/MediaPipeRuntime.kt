package com.mingeek.forge.runtime.mediapipe

import android.content.Context
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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MediaPipeRuntime(
    private val appContext: Context,
) : InferenceRuntime {

    override val id: RuntimeId = RuntimeId.MEDIAPIPE

    override val supportedFormats: Set<ModelFormat> = setOf(ModelFormat.MEDIAPIPE_TASK)

    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsStreaming = true,
        supportsToolCalls = false,
        supportsVision = false,
        supportsKvCacheReuse = false,
        supportedAccelerators = setOf(
            RuntimeCapabilities.Accelerator.CPU,
            RuntimeCapabilities.Accelerator.GPU,
        ),
    )

    private class SessionData(val handle: ModelHandle) {
        @Volatile var llm: LlmInference? = null
        @Volatile var emitter: ((String, Boolean) -> Unit)? = null
    }

    private val sessions = ConcurrentHashMap<SessionId, SessionData>()

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel = withContext(Dispatchers.IO) {
        require(model.format == ModelFormat.MEDIAPIPE_TASK) {
            "MediaPipe runtime only supports .task models"
        }

        val sessionId = SessionId(UUID.randomUUID().toString())
        val data = SessionData(model)
        sessions[sessionId] = data

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.modelPath)
            .setMaxTokens(config.contextLength.coerceAtLeast(512))
            .setResultListener { partial, done ->
                data.emitter?.invoke(partial.orEmpty(), done)
            }
            .build()

        data.llm = LlmInference.createFromOptions(appContext, options)

        LoadedModel(
            sessionId = sessionId,
            handle = model,
            runtimeId = id,
            capabilities = capabilities,
            chatTemplate = null,
        )
    }

    override fun generate(session: SessionId, prompt: Prompt): Flow<Token> = callbackFlow {
        val s = sessions[session] ?: error("MediaPipe session not loaded: $session")
        val llm = s.llm ?: error("MediaPipe inference not initialized")

        val fullPrompt = buildString {
            prompt.systemPrompt?.let { append(it); append("\n\n") }
            append(prompt.text)
        }

        var emitted = 0
        s.emitter = { partial, done ->
            if (partial.isNotEmpty()) {
                trySend(Token.Text(partial))
                emitted++
            }
            if (done) {
                trySend(
                    Token.Done(
                        finishReason = Token.FinishReason.STOP,
                        usage = Token.TokenUsage(
                            promptTokens = 0,
                            completionTokens = emitted,
                            totalTokens = emitted,
                        ),
                    )
                )
                close()
            }
        }

        llm.generateResponseAsync(fullPrompt)

        awaitClose { s.emitter = null }
    }.flowOn(Dispatchers.IO)

    override suspend fun unload(session: SessionId): Unit = withContext(Dispatchers.IO) {
        val s = sessions.remove(session) ?: return@withContext
        s.emitter = null
        s.llm?.close()
        s.llm = null
    }
}
