// -----------------------------------------------------------------------------
// jaagruk_llm.cpp — JNI bridge to llama.cpp for on-device safety coaching.
//
// Adapted from the Infinity AI Command Center bridge by Palak Rai, with the
// concurrency fixes from that work retained and the following changes:
//
//   * Greedy decoding by default. These answers are grounded in bundled safety
//     text and are checked by AnswerGuard afterwards; sampling temperature buys
//     variety nobody asked for and makes output irreproducible for a given
//     prompt, which would make the guard's behaviour impossible to pin in a test.
//   * Context-overflow is refused up front. A grounded prompt carries up to four
//     source passages, so it is long by construction, and silently truncating it
//     would ground an answer in text the caller believes it supplied.
//   * A stop reason is reported rather than inferred, so a cancelled generation
//     and a finished one are distinguishable on the Kotlin side.
//
// Threading model, unchanged in substance from the original:
//   g_state_mutex guards the model/context pointers.
//   g_gen_mutex serialises generations, and unloadModel() takes it so a free
//   cannot race a running decode.
//   g_stop is checked between tokens and between prefill chunks.
// -----------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <algorithm>
#include <pthread.h>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#define LOG_TAG "JaagrukLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Stop reasons. Mirrored by StopReason in LlmEngine.kt; the ordinals are the contract.
static const jint STOP_END_OF_TURN = 0;
static const jint STOP_TOKEN_LIMIT = 1;
static const jint STOP_CANCELLED   = 2;

static pthread_mutex_t   g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t   g_gen_mutex   = PTHREAD_MUTEX_INITIALIZER;
static llama_model*      g_model       = nullptr;
static llama_context*    g_ctx         = nullptr;
static std::atomic<bool> g_stop{false};
static std::atomic<int>  g_context_size{0};
static bool              g_backend     = false;
static JavaVM*           g_jvm         = nullptr;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

struct GenContext {
    std::string prompt;
    int         maxTokens;
    float       temperature;
    float       topP;
    int         seed;
    jobject     callback;
    jmethodID   onToken;
    jmethodID   onComplete;
    jmethodID   onError;
};

// Builds the sampler chain. Greedy unless a positive temperature was asked for.
static llama_sampler* build_sampler(const GenContext* ctx) {
    auto params = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(params);
    if (!chain) return nullptr;

    if (ctx->temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(ctx->topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(ctx->temperature));
    llama_sampler_chain_add(
        chain,
        llama_sampler_init_dist(ctx->seed >= 0 ? (uint32_t) ctx->seed : LLAMA_DEFAULT_SEED));
    return chain;
}

static void run_generation(JNIEnv* env, GenContext* ctx) {
    // Cleared before any early return, so a stop() that arrived while idle cannot
    // make the next generation exit immediately.
    g_stop.store(false);

    auto fireError = [&](const char* message) {
        LOGE("%s", message);
        jstring jmessage = env->NewStringUTF(message);
        env->CallVoidMethod(ctx->callback, ctx->onError, jmessage);
        env->DeleteLocalRef(jmessage);
    };

    // Snapshot both pointers under the lock. unloadModel() nulls them under the
    // same lock and blocks on g_gen_mutex, so non-null here stays valid for the
    // duration of this call.
    pthread_mutex_lock(&g_state_mutex);
    llama_model*   model = g_model;
    llama_context* context = g_ctx;
    pthread_mutex_unlock(&g_state_mutex);

    if (!model || !context) {
        fireError("no model is loaded");
        return;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    const int needed = -llama_tokenize(
        vocab, ctx->prompt.c_str(), (int32_t) ctx->prompt.size(), nullptr, 0, true, true);
    if (needed <= 0) {
        fireError("could not measure the prompt");
        return;
    }

    // Refuse rather than truncate. A grounded prompt that does not fit is a bug in
    // the budget calculation, and answering from a silently clipped prompt would
    // produce an answer grounded in less than the caller believes.
    const int contextSize = g_context_size.load();
    if (needed + ctx->maxTokens > contextSize) {
        std::string message = "prompt of " + std::to_string(needed) + " tokens plus " +
                              std::to_string(ctx->maxTokens) + " reserved for output exceeds the " +
                              std::to_string(contextSize) + " token context";
        fireError(message.c_str());
        return;
    }

    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(
            vocab, ctx->prompt.c_str(), (int32_t) ctx->prompt.size(),
            tokens.data(), needed, true, true) < 0) {
        fireError("tokenisation failed");
        return;
    }
    LOGI("prompt %d tokens, context %d, budget %d", needed, contextSize, ctx->maxTokens);

    llama_memory_clear(llama_get_memory(context), true);

    // Chunked prefill. The state lock is taken per chunk rather than held across
    // the whole loop, so unloadModel() can acquire it between chunks instead of
    // waiting out a multi-second prefill.
    const int chunkSize = 512;
    for (int offset = 0; offset < needed; offset += chunkSize) {
        if (g_stop.load()) {
            env->CallVoidMethod(ctx->callback, ctx->onComplete, STOP_CANCELLED);
            return;
        }
        pthread_mutex_lock(&g_state_mutex);
        llama_context* live = g_ctx;
        pthread_mutex_unlock(&g_state_mutex);
        if (!live) {
            env->CallVoidMethod(ctx->callback, ctx->onComplete, STOP_CANCELLED);
            return;
        }
        const int count = std::min(chunkSize, needed - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(live, batch) != 0) {
            fireError("prompt evaluation failed");
            return;
        }
    }

    llama_sampler* sampler = build_sampler(ctx);
    if (!sampler) {
        fireError("could not initialise the sampler");
        return;
    }

    jint reason = STOP_TOKEN_LIMIT;
    int produced = 0;

    for (int index = 0; index < ctx->maxTokens; index++) {
        if (g_stop.load()) {
            reason = STOP_CANCELLED;
            break;
        }
        pthread_mutex_lock(&g_state_mutex);
        llama_context* live = g_ctx;
        pthread_mutex_unlock(&g_state_mutex);
        if (!live) {
            reason = STOP_CANCELLED;
            break;
        }

        const llama_token token = llama_sampler_sample(sampler, live, -1);

        // Gemma marks <end_of_turn> as end-of-generation, so this is what stops a
        // chat turn. Checked before the piece is emitted: the marker is not text.
        if (llama_vocab_is_eog(vocab, token)) {
            reason = STOP_END_OF_TURN;
            break;
        }

        char buffer[512] = {};
        const int length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
        if (length < 0) {
            LOGW("token %d did not convert to text, stopping", token);
            reason = STOP_END_OF_TURN;
            break;
        }

        jstring piece = env->NewStringUTF(std::string(buffer, length).c_str());
        env->CallVoidMethod(ctx->callback, ctx->onToken, piece);
        env->DeleteLocalRef(piece);
        if (env->ExceptionCheck()) {
            // The Kotlin side threw, usually because its channel closed. Treat it as
            // cancellation rather than letting the exception ride back through JNI.
            env->ExceptionClear();
            reason = STOP_CANCELLED;
            break;
        }
        produced++;

        llama_batch next = llama_batch_get_one(&(const_cast<llama_token&>(token)), 1);
        if (llama_decode(live, next) != 0) {
            fireError("token evaluation failed");
            llama_sampler_free(sampler);
            return;
        }
    }

    llama_sampler_free(sampler);
    LOGI("generated %d tokens, stop reason %d", produced, reason);
    env->CallVoidMethod(ctx->callback, ctx->onComplete, reason);
}

static void* generation_thread(void* argument) {
    GenContext* ctx = static_cast<GenContext*>(argument);

    // One generation at a time. Two threads sharing a context corrupts the KV cache.
    pthread_mutex_lock(&g_gen_mutex);

    JNIEnv* env = nullptr;
    JavaVMAttachArgs attachArgs = { JNI_VERSION_1_6, "jaagruk-llm", nullptr };
    if (g_jvm->AttachCurrentThread(&env, &attachArgs) != JNI_OK || !env) {
        LOGE("could not attach the inference thread to the JVM");
        pthread_mutex_unlock(&g_gen_mutex);
        // No env, so the global ref cannot be released. One leaked ref on an
        // unrecoverable JVM error is the lesser problem.
        delete ctx;
        return nullptr;
    }

    run_generation(env, ctx);

    env->DeleteGlobalRef(ctx->callback);
    delete ctx;
    g_jvm->DetachCurrentThread();
    pthread_mutex_unlock(&g_gen_mutex);
    return nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jint contextTokens, jint threads) {

    pthread_mutex_lock(&g_gen_mutex);
    pthread_mutex_lock(&g_state_mutex);
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    pthread_mutex_unlock(&g_state_mutex);
    pthread_mutex_unlock(&g_gen_mutex);

    if (!g_backend) {
        llama_backend_init();
        ggml_backend_register(ggml_backend_cpu_reg());
        g_backend = true;
        LOGI("llama backend initialised with the CPU device");
    }

    llama_log_set([](ggml_log_level level, const char* text, void*) {
        if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "[llama] %s", text);
        }
    }, nullptr);

    const std::string path = jstr(env, modelPath);
    LOGI("loading %s", path.c_str());

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    // mmap stays on: the weights are read from the file as they are touched, so a
    // 769 MiB model costs no copy and no second 769 MiB of storage. This is the whole
    // reason the model is a file on disk rather than an APK asset.
    modelParams.use_mmap = true;
    modelParams.use_mlock = false;

    llama_model* model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!model) {
        LOGE("model load failed");
        return JNI_FALSE;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx           = (uint32_t) contextTokens;
    contextParams.n_batch         = 512;
    contextParams.n_ubatch        = 512;
    contextParams.n_threads       = (uint32_t) threads;
    contextParams.n_threads_batch = (uint32_t) threads;

    llama_context* context = llama_init_from_model(model, contextParams);
    if (!context) {
        LOGE("context creation failed");
        llama_model_free(model);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_state_mutex);
    g_model = model;
    g_ctx   = context;
    pthread_mutex_unlock(&g_state_mutex);
    g_context_size.store(contextTokens);

    LOGI("model ready, context %d tokens, %d threads", contextTokens, threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject, jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jint seed, jobject callback) {

    jclass type = env->GetObjectClass(callback);
    jmethodID onToken    = env->GetMethodID(type, "onToken",    "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(type, "onComplete", "(I)V");
    jmethodID onError    = env->GetMethodID(type, "onError",    "(Ljava/lang/String;)V");
    if (!onToken || !onComplete || !onError) {
        LOGE("callback interface does not match the expected signatures");
        return;
    }

    GenContext* ctx  = new GenContext();
    ctx->prompt      = jstr(env, prompt);
    ctx->maxTokens   = (int) maxTokens;
    ctx->temperature = (float) temperature;
    ctx->topP        = (float) topP;
    ctx->seed        = (int) seed;
    ctx->callback    = env->NewGlobalRef(callback);
    ctx->onToken     = onToken;
    ctx->onComplete  = onComplete;
    ctx->onError     = onError;

    pthread_t thread;
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&thread, &attributes, generation_thread, ctx) != 0) {
        LOGE("could not start the inference thread");
        jstring message = env->NewStringUTF("could not start the inference thread");
        env->CallVoidMethod(callback, onError, message);
        env->DeleteLocalRef(message);
        env->DeleteGlobalRef(ctx->callback);
        delete ctx;
    }
    pthread_attr_destroy(&attributes);
}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeStop(JNIEnv*, jobject) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeUnloadModel(JNIEnv*, jobject) {
    // Waits for any running generation. g_gen_mutex is held for the whole life of
    // the inference thread, so taking it here means no decode is in flight.
    pthread_mutex_lock(&g_gen_mutex);
    pthread_mutex_lock(&g_state_mutex);
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    pthread_mutex_unlock(&g_state_mutex);
    pthread_mutex_unlock(&g_gen_mutex);
    g_context_size.store(0);
    LOGI("model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeIsLoaded(JNIEnv*, jobject) {
    pthread_mutex_lock(&g_state_mutex);
    const jboolean loaded = (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_state_mutex);
    return loaded;
}

JNIEXPORT jint JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeContextTokens(JNIEnv*, jobject) {
    return (jint) g_context_size.load();
}

} // extern "C"
