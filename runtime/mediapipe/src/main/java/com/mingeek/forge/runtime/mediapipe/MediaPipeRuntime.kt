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
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaPipe LLM Inference runtime. Two non-obvious decisions:
 *
 * 1. **Per-call sampling.** Temperature / topK / topP belong on
 *    [LlmInferenceSession], not [LlmInference]. We rebuild a session
 *    per [generate] so the [Prompt]'s sampling parameters actually
 *    take effect — earlier the values were discarded because we only
 *    set them once at load time.
 *
 * 2. **Mutex per LlmInference.** The result listener is registered on
 *    the LlmInference instance, not the session, so concurrent
 *    generates on the same loaded model would race over a single
 *    emitter slot. The mutex serializes per-instance — different
 *    [InstalledModel]s loaded into the runtime each get their own
 *    LlmInference (and own mutex) so Compare's two-pane parallelism
 *    still works.
 */
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

    private class SessionData(
        val handle: ModelHandle,
        val maxTopK: Int,
    ) {
        @Volatile var llm: LlmInference? = null
        // Set by the active generate() call; cleared on completion.
        @Volatile var emitter: ((String, Boolean) -> Unit)? = null
        // Serializes generate() calls per loaded LlmInference because the
        // result listener is shared across sessions of the same instance.
        val mutex: Mutex = Mutex()
    }

    private val sessions = ConcurrentHashMap<SessionId, SessionData>()

    override suspend fun load(model: ModelHandle, config: LoadConfig): LoadedModel = withContext(Dispatchers.IO) {
        require(model.format == ModelFormat.MEDIAPIPE_TASK) {
            "MediaPipe runtime only supports .task models"
        }

        val sessionId = SessionId(UUID.randomUUID().toString())
        val data = SessionData(model, maxTopK = MAX_TOP_K)
        sessions[sessionId] = data

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.modelPath)
            .setMaxTokens(config.contextLength.coerceAtLeast(512))
            .setMaxTopK(MAX_TOP_K)
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

        // Acquire the per-instance mutex before wiring the emitter so a
        // previous in-flight generate finishes cleanly before we start.
        s.mutex.withLock {
            val fullPrompt = buildString {
                prompt.systemPrompt?.let { append(it); append("\n\n") }
                append(prompt.text)
            }

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(prompt.temperature)
                .setTopP(prompt.topP)
                .setTopK(DEFAULT_TOP_K)
                .build()

            val mpSession = LlmInferenceSession.createFromOptions(llm, sessionOptions)
            val finished = CompletableDeferred<Unit>()
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
                        ),
                    )
                    finished.complete(Unit)
                    close()
                }
            }

            try {
                mpSession.addQueryChunk(fullPrompt)
                mpSession.generateResponseAsync()
                // Block the mutex until the result listener fires done so the
                // next generate doesn't stomp our emitter mid-stream.
                finished.await()
            } finally {
                s.emitter = null
                runCatching { mpSession.close() }
            }
        }

        awaitClose { /* mutex+emitter cleanup already done above */ }
    }.flowOn(Dispatchers.IO)

    override suspend fun unload(session: SessionId): Unit = withContext(Dispatchers.IO) {
        val s = sessions.remove(session) ?: return@withContext
        s.emitter = null
        s.llm?.close()
        s.llm = null
    }

    private companion object {
        // setMaxTopK caps what individual sessions can request; pick a
        // ceiling that covers anything we'd reasonably want.
        const val MAX_TOP_K = 40
        const val DEFAULT_TOP_K = 40
    }
}
