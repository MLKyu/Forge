package com.mingeek.forge.runtime.registry

import com.mingeek.forge.domain.ModelFormat
import com.mingeek.forge.domain.RuntimeId
import com.mingeek.forge.runtime.core.InferenceRuntime

class RuntimeRegistry(runtimes: List<InferenceRuntime>) {

    private val byId: Map<RuntimeId, InferenceRuntime> = runtimes.associateBy { it.id }

    val all: List<InferenceRuntime> = runtimes

    fun get(id: RuntimeId): InferenceRuntime? = byId[id]

    fun pick(format: ModelFormat, preferred: RuntimeId? = null): InferenceRuntime? {
        if (preferred != null) {
            byId[preferred]?.takeIf { format in it.supportedFormats }?.let { return it }
        }
        return all.firstOrNull { format in it.supportedFormats }
    }

    /** Every runtime registered for [format], in registration order. */
    fun runtimesFor(format: ModelFormat): List<InferenceRuntime> =
        all.filter { format in it.supportedFormats }
}
