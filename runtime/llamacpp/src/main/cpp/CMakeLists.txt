cmake_minimum_required(VERSION 3.22)
project(llama-jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Disable llama.cpp build features we don't need
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# GGML backends — CPU only for M2 (Vulkan/QNN come later)
set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE     OFF CACHE BOOL "" FORCE)
set(GGML_CUDA          OFF CACHE BOOL "" FORCE)
set(GGML_METAL         OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN        OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS          OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)

# CPU optimizations for arm64-v8a
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

include(FetchContent)
# Pin to a specific upstream commit. Bumping requires manual review — moving
# llama.cpp forward sometimes breaks the JNI surface or quantization compat.
# To upgrade: pick an upstream commit, bump GIT_TAG, run a full build + a
# local Chat smoke-test, then commit the new SHA.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        d77599234ea6e498775aeadbce665eece5bd98cd
)
FetchContent_MakeAvailable(llama_cpp)

add_library(llama-jni SHARED
    llama_jni.cpp
)

target_link_libraries(llama-jni
    PRIVATE
    llama
    log
    android
)

target_compile_options(llama-jni PRIVATE -Wno-unused-function)
