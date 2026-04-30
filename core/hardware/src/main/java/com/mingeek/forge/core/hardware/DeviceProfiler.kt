package com.mingeek.forge.core.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

class DeviceProfiler(private val appContext: Context) {

    fun snapshot(): DeviceProfile {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val storage = StatFs(appContext.filesDir.absolutePath)
        val free = storage.availableBytes

        val socMfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

        val accelerators = inferAccelerators(socMfr, socModel)

        return DeviceProfile(
            deviceModel = Build.MODEL ?: Build.DEVICE ?: "unknown",
            socManufacturer = socMfr,
            socModel = socModel,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = mem.totalMem,
            availableRamBytes = mem.availMem,
            freeStorageBytes = free,
            accelerators = accelerators,
        )
    }

    private fun inferAccelerators(
        socMfr: String?,
        socModel: String?,
    ): Set<DeviceProfile.Accelerator> {
        val set = mutableSetOf(DeviceProfile.Accelerator.CPU)
        // Vulkan presence is hard to detect without enumerating; assume yes on modern devices
        set += DeviceProfile.Accelerator.GPU_VULKAN

        val mfrLower = socMfr?.lowercase().orEmpty()
        val modelLower = socModel?.lowercase().orEmpty()

        when {
            "qualcomm" in mfrLower || modelLower.startsWith("sm") || "snapdragon" in modelLower ->
                set += DeviceProfile.Accelerator.NPU_QUALCOMM
            "samsung" in mfrLower || "exynos" in modelLower ->
                set += DeviceProfile.Accelerator.NPU_SAMSUNG
            "mediatek" in mfrLower || modelLower.startsWith("mt") ->
                set += DeviceProfile.Accelerator.NPU_MEDIATEK
        }
        return set
    }
}
