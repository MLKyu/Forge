package com.mingeek.forge.runtime.llamacpp

internal object LlamaCppNative {

    init {
        System.loadLibrary("llama-jni")
    }

    @JvmStatic external fun nativeInit(): Boolean

    @JvmStatic external fun nativeLoadModel(path: String, nGpuLayers: Int): Long
    @JvmStatic external fun nativeFreeModel(modelHandle: Long)

    @JvmStatic external fun nativeNewContext(
        modelHandle: Long,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
    ): Long
    @JvmStatic external fun nativeFreeContext(ctxHandle: Long)
    @JvmStatic external fun nativeResetContext(ctxHandle: Long)
    @JvmStatic external fun nativeContextSize(ctxHandle: Long): Int

    @JvmStatic external fun nativeNewSampler(
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Long,
    ): Long
    @JvmStatic external fun nativeFreeSampler(samplerHandle: Long)

    @JvmStatic external fun nativeTokenize(
        modelHandle: Long,
        text: String,
        addBos: Boolean,
        parseSpecial: Boolean,
    ): IntArray?

    @JvmStatic external fun nativeDecodeTokens(ctxHandle: Long, tokens: IntArray): Boolean
    @JvmStatic external fun nativeDecodeToken(ctxHandle: Long, token: Int): Boolean

    @JvmStatic external fun nativeSampleNext(ctxHandle: Long, samplerHandle: Long): Int

    @JvmStatic external fun nativeIsEog(modelHandle: Long, token: Int): Boolean
    @JvmStatic external fun nativeTokenToPiece(modelHandle: Long, token: Int): String

    @JvmStatic external fun nativeGetMetadataString(modelHandle: Long, key: String): String?
}
