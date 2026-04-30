package com.mingeek.forge.core.hardware

data class DeviceProfile(
    val deviceModel: String,
    val socManufacturer: String?,
    val socModel: String?,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val accelerators: Set<Accelerator>,
) {
    enum class Accelerator { CPU, GPU_VULKAN, NPU_QUALCOMM, NPU_SAMSUNG, NPU_MEDIATEK }

    val hasNpu: Boolean
        get() = accelerators.any { it.name.startsWith("NPU_") }
}
