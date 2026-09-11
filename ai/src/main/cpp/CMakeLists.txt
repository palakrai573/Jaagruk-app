cmake_minimum_required(VERSION 3.22.1)
project("jaagruk_llm")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
# Optimisation flags
#
# -O3 without -ffast-math. -ffast-math implies -ffinite-math-only, and ggml uses
# INFINITY and -INFINITY extensively for masked attention scores. Compiling it
# under finite-math produces a library that loads, runs, and returns wrong
# numbers -- the worst possible failure mode, so the flag is spelled out here
# rather than left to whatever a future toolchain defaults to.
# -----------------------------------------------------------------------------
set(JAAGRUK_MATH_FLAGS "-O3 -fno-finite-math-only")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${JAAGRUK_MATH_FLAGS}")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   ${JAAGRUK_MATH_FLAGS}")

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama")

# -----------------------------------------------------------------------------
# The vendored sources are optional.
#
# When they are absent a stub library is built that reports "unavailable" through
# the same JNI surface. That keeps `:ai` compiling on a checkout without the
# vendored tree, and it means a device with no native build degrades to
# AiCapability.UNSUPPORTED_DEVICE rather than crashing on class load.
# -----------------------------------------------------------------------------
if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(WARNING "jaagruk: llama.cpp sources not found at ${LLAMA_DIR} -- building the stub")
    add_library(jaagruk_llm SHARED jaagruk_llm_stub.cpp)
    target_link_libraries(jaagruk_llm android log)
    return()
endif()

message(STATUS "jaagruk: building the llama.cpp CPU backend for ${ANDROID_ABI}")

# -----------------------------------------------------------------------------
# Per-ABI CPU kernels
#
# Both arch trees expose the same three translation units. Selecting the wrong
# one fails at link time on missing symbols, so this is a hard error rather than
# a silent fallback: an ABI nobody has provided kernels for should not produce a
# library that half works.
# -----------------------------------------------------------------------------
set(GGML_CPU_DIR "${LLAMA_DIR}/ggml/src/ggml-cpu")

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_ARCH_DIR "${GGML_CPU_DIR}/arch/arm")
elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    set(GGML_ARCH_DIR "${GGML_CPU_DIR}/arch/x86")
else()
    message(FATAL_ERROR "jaagruk: no ggml CPU kernels vendored for ABI '${ANDROID_ABI}'")
endif()

set(GGML_ARCH_SOURCES
    ${GGML_ARCH_DIR}/quants.c
    ${GGML_ARCH_DIR}/cpu-feats.cpp
    ${GGML_ARCH_DIR}/repack.cpp
)

# -----------------------------------------------------------------------------
# ggml: tensor library plus the CPU backend
#
# AMX (amx/, mmq.cpp) is deliberately excluded. It is an Intel matrix-extension
# path that either fails to compile or compiles to dead code on every ABI this
# app ships, and the extra-buffer-type registry guards its absence already.
# kleidiai/ and spacemit/ are excluded for the same reason: optional accelerators
# for hardware outside this deployment.
# -----------------------------------------------------------------------------
add_library(ggml_cpu STATIC
    ${LLAMA_DIR}/ggml/src/ggml.c
    ${LLAMA_DIR}/ggml/src/ggml.cpp
    ${LLAMA_DIR}/ggml/src/gguf.cpp
    ${LLAMA_DIR}/ggml/src/ggml-alloc.c
    ${LLAMA_DIR}/ggml/src/ggml-backend.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend-dl.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend-meta.cpp
    ${LLAMA_DIR}/ggml/src/ggml-backend-reg.cpp
    ${LLAMA_DIR}/ggml/src/ggml-opt.cpp
    ${LLAMA_DIR}/ggml/src/ggml-quants.c
    ${LLAMA_DIR}/ggml/src/ggml-threading.cpp
    ${GGML_CPU_DIR}/ggml-cpu.c
    ${GGML_CPU_DIR}/ggml-cpu.cpp
    ${GGML_CPU_DIR}/quants.c
    ${GGML_CPU_DIR}/traits.cpp
    ${GGML_CPU_DIR}/ops.cpp
    ${GGML_CPU_DIR}/binary-ops.cpp
    ${GGML_CPU_DIR}/unary-ops.cpp
    ${GGML_CPU_DIR}/vec.cpp
    ${GGML_CPU_DIR}/repack.cpp
    ${GGML_CPU_DIR}/hbm.cpp
    ${GGML_CPU_DIR}/llamafile/sgemm.cpp
    ${GGML_ARCH_SOURCES}
)

target_include_directories(ggml_cpu PUBLIC
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
    ${GGML_CPU_DIR}
)

# ggml.c expects these to be defined by the build system. Upstream fills them
# from git; a vendored tree has no git metadata, so they are pinned here.
target_compile_definitions(ggml_cpu PUBLIC
    GGML_VERSION="0.11.1"
    GGML_COMMIT="vendored-jaagruk"
    GGML_USE_CPU
    GGML_BACKEND_DL=0
    NDEBUG
)

# -----------------------------------------------------------------------------
# llama: model loading, graph building, sampling
# -----------------------------------------------------------------------------
file(GLOB LLAMA_MODEL_SOURCES "${LLAMA_DIR}/src/models/*.cpp")

add_library(llama_core STATIC
    ${LLAMA_DIR}/src/llama.cpp
    ${LLAMA_DIR}/src/llama-adapter.cpp
    ${LLAMA_DIR}/src/llama-arch.cpp
    ${LLAMA_DIR}/src/llama-batch.cpp
    ${LLAMA_DIR}/src/llama-chat.cpp
    ${LLAMA_DIR}/src/llama-context.cpp
    ${LLAMA_DIR}/src/llama-cparams.cpp
    ${LLAMA_DIR}/src/llama-grammar.cpp
    ${LLAMA_DIR}/src/llama-graph.cpp
    ${LLAMA_DIR}/src/llama-hparams.cpp
    ${LLAMA_DIR}/src/llama-impl.cpp
    ${LLAMA_DIR}/src/llama-io.cpp
    ${LLAMA_DIR}/src/llama-kv-cache.cpp
    ${LLAMA_DIR}/src/llama-kv-cache-iswa.cpp
    ${LLAMA_DIR}/src/llama-memory.cpp
    ${LLAMA_DIR}/src/llama-memory-hybrid.cpp
    ${LLAMA_DIR}/src/llama-memory-hybrid-iswa.cpp
    ${LLAMA_DIR}/src/llama-memory-recurrent.cpp
    ${LLAMA_DIR}/src/llama-mmap.cpp
    ${LLAMA_DIR}/src/llama-model.cpp
    ${LLAMA_DIR}/src/llama-model-loader.cpp
    ${LLAMA_DIR}/src/llama-model-saver.cpp
    ${LLAMA_DIR}/src/llama-quant.cpp
    ${LLAMA_DIR}/src/llama-sampler.cpp
    ${LLAMA_DIR}/src/llama-vocab.cpp
    ${LLAMA_DIR}/src/unicode.cpp
    ${LLAMA_DIR}/src/unicode-data.cpp
    ${LLAMA_MODEL_SOURCES}
)

target_include_directories(llama_core PUBLIC
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/src/models
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
)

target_compile_definitions(llama_core PRIVATE
    GGML_USE_CPU
    GGML_BACKEND_DL=0
    NDEBUG
)

target_link_libraries(llama_core ggml_cpu)

# -----------------------------------------------------------------------------
# The JNI bridge
# -----------------------------------------------------------------------------
add_library(jaagruk_llm SHARED jaagruk_llm.cpp)

target_include_directories(jaagruk_llm PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/ggml/include
)

target_compile_definitions(jaagruk_llm PRIVATE
    GGML_USE_CPU
    GGML_BACKEND_DL=0
)

target_link_libraries(jaagruk_llm llama_core ggml_cpu android log dl)
