#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

bool g_backend_initialized = false;

void ensure_backend() {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
}

std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeInit(
    JNIEnv* /*env*/, jclass /*clz*/) {
    ensure_backend();
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeLoadModel(
    JNIEnv* env, jclass /*clz*/, jstring jpath, jint n_gpu_layers) {
    ensure_backend();
    std::string path = jstring_to_utf8(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", path.c_str());
        return 0;
    }
    LOGI("loaded model %s", path.c_str());
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeFreeModel(
    JNIEnv* /*env*/, jclass /*clz*/, jlong handle) {
    auto* model = reinterpret_cast<llama_model*>(handle);
    if (model) llama_model_free(model);
}

JNIEXPORT jlong JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeNewContext(
    JNIEnv* /*env*/, jclass /*clz*/,
    jlong model_h, jint n_ctx, jint n_threads, jint n_batch) {
    auto* model = reinterpret_cast<llama_model*>(model_h);
    if (!model) return 0;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t) n_ctx;
    cparams.n_batch   = (uint32_t) n_batch;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeFreeContext(
    JNIEnv* /*env*/, jclass /*clz*/, jlong ctx_h) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    if (ctx) llama_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeResetContext(
    JNIEnv* /*env*/, jclass /*clz*/, jlong ctx_h) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    if (!ctx) return;
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) llama_memory_clear(mem, true);
}

JNIEXPORT jlong JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeNewSampler(
    JNIEnv* /*env*/, jclass /*clz*/,
    jfloat temp, jfloat top_p, jint top_k, jlong seed) {

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(sparams);

    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    if (temp > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));

    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT void JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeFreeSampler(
    JNIEnv* /*env*/, jclass /*clz*/, jlong sampler_h) {
    auto* sampler = reinterpret_cast<llama_sampler*>(sampler_h);
    if (sampler) llama_sampler_free(sampler);
}

JNIEXPORT jintArray JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeTokenize(
    JNIEnv* env, jclass /*clz*/,
    jlong model_h, jstring jtext, jboolean add_bos, jboolean parse_special) {
    auto* model = reinterpret_cast<llama_model*>(model_h);
    if (!model) return nullptr;
    const llama_vocab* vocab = llama_model_get_vocab(model);
    std::string text = jstring_to_utf8(env, jtext);

    int n_estimate = (int) text.size() + 4;
    std::vector<llama_token> tokens(n_estimate);
    int n = llama_tokenize(vocab, text.c_str(), (int) text.size(),
                           tokens.data(), (int) tokens.size(),
                           add_bos == JNI_TRUE, parse_special == JNI_TRUE);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int) text.size(),
                           tokens.data(), (int) tokens.size(),
                           add_bos == JNI_TRUE, parse_special == JNI_TRUE);
        if (n < 0) {
            LOGE("tokenize failed: n=%d", n);
            return nullptr;
        }
    }
    tokens.resize(n);

    jintArray jarr = env->NewIntArray(n);
    env->SetIntArrayRegion(jarr, 0, n, reinterpret_cast<const jint*>(tokens.data()));
    return jarr;
}

JNIEXPORT jboolean JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeDecodeTokens(
    JNIEnv* env, jclass /*clz*/, jlong ctx_h, jintArray jtokens) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    if (!ctx) return JNI_FALSE;
    jsize n = env->GetArrayLength(jtokens);
    if (n <= 0) return JNI_TRUE;

    std::vector<llama_token> tokens(n);
    env->GetIntArrayRegion(jtokens, 0, n, reinterpret_cast<jint*>(tokens.data()));

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    int rc = llama_decode(ctx, batch);
    if (rc != 0) {
        LOGE("llama_decode prompt failed: %d", rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeSampleNext(
    JNIEnv* /*env*/, jclass /*clz*/,
    jlong ctx_h, jlong sampler_h) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    auto* sampler = reinterpret_cast<llama_sampler*>(sampler_h);
    if (!ctx || !sampler) return -1;

    llama_token id = llama_sampler_sample(sampler, ctx, -1);
    llama_sampler_accept(sampler, id);
    return (jint) id;
}

JNIEXPORT jboolean JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeIsEog(
    JNIEnv* /*env*/, jclass /*clz*/, jlong model_h, jint token) {
    auto* model = reinterpret_cast<llama_model*>(model_h);
    if (!model) return JNI_TRUE;
    const llama_vocab* vocab = llama_model_get_vocab(model);
    return llama_vocab_is_eog(vocab, (llama_token) token) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeTokenToPiece(
    JNIEnv* env, jclass /*clz*/, jlong model_h, jint token) {
    auto* model = reinterpret_cast<llama_model*>(model_h);
    if (!model) return env->NewStringUTF("");
    const llama_vocab* vocab = llama_model_get_vocab(model);

    char buf[256];
    int n = llama_token_to_piece(vocab, (llama_token) token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::vector<char> big(-n + 1);
        n = llama_token_to_piece(vocab, (llama_token) token, big.data(), (int) big.size(), 0, true);
        if (n < 0) return env->NewStringUTF("");
        return env->NewStringUTF(std::string(big.data(), n).c_str());
    }
    return env->NewStringUTF(std::string(buf, n).c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeDecodeToken(
    JNIEnv* /*env*/, jclass /*clz*/, jlong ctx_h, jint token) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    if (!ctx) return JNI_FALSE;
    llama_token tok = (llama_token) token;
    llama_batch batch = llama_batch_get_one(&tok, 1);
    int rc = llama_decode(ctx, batch);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeContextSize(
    JNIEnv* /*env*/, jclass /*clz*/, jlong ctx_h) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_h);
    if (!ctx) return 0;
    return (jint) llama_n_ctx(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_mingeek_forge_runtime_llamacpp_LlamaCppNative_nativeGetMetadataString(
    JNIEnv* env, jclass /*clz*/, jlong model_h, jstring jkey) {
    auto* model = reinterpret_cast<llama_model*>(model_h);
    if (!model) return nullptr;
    std::string key = jstring_to_utf8(env, jkey);

    std::vector<char> buf(8192);
    int n = llama_model_meta_val_str(model, key.c_str(), buf.data(), buf.size());
    if (n < 0) return nullptr;
    if ((size_t) n >= buf.size()) {
        buf.resize(n + 1);
        n = llama_model_meta_val_str(model, key.c_str(), buf.data(), buf.size());
        if (n < 0) return nullptr;
    }
    return env->NewStringUTF(std::string(buf.data(), n).c_str());
}

} // extern "C"
