package com.mingeek.forge.domain

enum class ModelFormat {
    GGUF,
    MLC,
    EXECUTORCH_PTE,
    MEDIAPIPE_TASK,
    SAFETENSORS,
    ONNX,
}

enum class Quant {
    F32,
    F16,
    BF16,
    Q8_0,
    Q6_K,
    Q5_K_M,
    Q4_K_M,
    Q4_0,
    INT8,
    INT4,
    MIXED,
    UNKNOWN,
}
