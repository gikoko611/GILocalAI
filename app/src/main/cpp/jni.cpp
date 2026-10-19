#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

static llama_model *g_model = nullptr;
static llama_context *g_c = nullptr;           // kept alive between replies (KV cache reuse)
static std::vector<llama_token> g_cache;       // tokens currently stored in the KV cache
static std::atomic<bool> g_stop{false};
static int g_ctx = 1024, g_threads = 4;
static bool g_init = false;

// Remove KV entries [a, b) of sequence 0 (b < 0 = to the end). Works with old and new llama.cpp names.
struct Pri0 {}; struct Pri1 : Pri0 {}; struct Pri2 : Pri1 {};
template <class C> static auto kvrm(C c, int a, int b, Pri2) -> decltype(llama_memory_seq_rm(llama_get_memory(c), 0, a, b)) { return llama_memory_seq_rm(llama_get_memory(c), 0, a, b); }
template <class C> static auto kvrm(C c, int a, int b, Pri1) -> decltype(llama_kv_self_seq_rm(c, 0, a, b)) { return llama_kv_self_seq_rm(c, 0, a, b); }
template <class C> static auto kvrm(C c, int a, int b, Pri0) -> decltype(llama_kv_cache_seq_rm(c, 0, a, b)) { return llama_kv_cache_seq_rm(c, 0, a, b); }

static void drop_ctx() {
    if (g_c) { llama_free(g_c); g_c = nullptr; }
    g_cache.clear();
}

static llama_context *make_ctx() {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = g_ctx; cp.n_batch = 256; cp.n_ubatch = 256;
    cp.n_threads = g_threads; cp.n_threads_batch = g_threads;
    return llama_init_from_model(g_model, cp);
}

static std::string bytes_of(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, (jbyte *) &s[0]);
    return s;
}

// length of the prefix that holds only complete UTF-8 characters
static size_t complete_len(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        i--; back++;
        unsigned char c = (unsigned char) s[i];
        if ((c & 0xC0) == 0x80) continue;
        int need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return back >= need ? n : i;
    }
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gikoko_gilocalai_Llama_unload(JNIEnv *, jobject) {
    drop_ctx();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gikoko_gilocalai_Llama_stop(JNIEnv *, jobject) { g_stop = true; }

extern "C" JNIEXPORT jstring JNICALL
Java_com_gikoko_gilocalai_Llama_load(JNIEnv *env, jobject, jstring path, jint ctx, jint threads) {
    if (!g_init) { llama_backend_init(); g_init = true; }
    drop_ctx();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_ctx = ctx; g_threads = threads;
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    g_model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    return env->NewStringUTF(g_model ? "" : "Could not load this model. Try a smaller or different GGUF file.");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gikoko_gilocalai_Llama_generate(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents,
                                         jfloat temp, jint maxTok, jobject cb) {
    if (!g_model) return env->NewStringUTF("No model loaded.");
    g_stop = false;
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    int nm = env->GetArrayLength(roles);
    std::vector<std::string> rs, cs;
    for (int i = 0; i < nm; i++) {
        jstring r = (jstring) env->GetObjectArrayElement(roles, i);
        const char *rc = env->GetStringUTFChars(r, nullptr);
        rs.push_back(rc);
        env->ReleaseStringUTFChars(r, rc);
        env->DeleteLocalRef(r);
        jbyteArray c = (jbyteArray) env->GetObjectArrayElement(contents, i);
        cs.push_back(bytes_of(env, c));
        env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> msgs;
    for (int i = 0; i < nm; i++) msgs.push_back({rs[i].c_str(), cs[i].c_str()});

    std::string prompt;
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    int n = -1;
    if (tmpl) {
        std::vector<char> buf(16384);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
        }
        if (n >= 0) prompt.assign(buf.data(), n);
    }
    if (n < 0) {
        for (int i = 0; i < nm; i++) prompt += rs[i] + ": " + cs[i] + "\n";
        prompt += "assistant: ";
    }

    int nt = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    if (nt <= 0) return env->NewStringUTF("Could not read the prompt.");
    std::vector<llama_token> toks(nt);
    if (llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), nt, true, true) < 0)
        return env->NewStringUTF("Could not read the prompt.");
    if (nt >= g_ctx - 16) return env->NewStringUTF("Chat is too long for this context size. Clear the chat or raise Context size in Settings.");
    int maxn = std::min((int) maxTok, g_ctx - nt);

    // reuse the KV cache: keep the common start of the old and new prompt, drop the rest
    if (!g_c) { g_c = make_ctx(); g_cache.clear(); }
    if (!g_c) return env->NewStringUTF("Not enough memory for this context size.");
    size_t keep = 0;
    while (keep < g_cache.size() && keep < toks.size() && g_cache[keep] == toks[keep]) keep++;
    if (keep >= toks.size()) keep = toks.size() - 1;
    if (!kvrm(g_c, (int) keep, -1, Pri2{})) {
        drop_ctx();
        g_c = make_ctx();
        if (!g_c) return env->NewStringUTF("Not enough memory for this context size.");
        keep = 0;
    }
    g_cache.resize(keep);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)V");
    std::string err, pending;

    for (size_t i = keep; i < toks.size() && !g_stop; i += 256) {
        int c = (int) std::min<size_t>(256, toks.size() - i);
        if (llama_decode(g_c, llama_batch_get_one(toks.data() + i, c)) != 0) { err = "Model failed while reading the prompt."; break; }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + c);
    }

    for (int k = 0; k < maxn && err.empty() && !g_stop; k++) {
        llama_token id = llama_sampler_sample(smpl, g_c, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[256];
        int len = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (len > 0) {
            pending.append(piece, len);
            size_t ok = complete_len(pending);
            if (ok > 0) {
                jbyteArray arr = env->NewByteArray((jsize) ok);
                env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
                env->CallVoidMethod(cb, mid, arr);
                env->DeleteLocalRef(arr);
                pending.erase(0, ok);
            }
        }
        if (llama_decode(g_c, llama_batch_get_one(&id, 1)) != 0) { err = "Model stopped unexpectedly."; break; }
        g_cache.push_back(id);
    }

    llama_sampler_free(smpl);
    if (!err.empty()) drop_ctx();   // start clean after an error
    return env->NewStringUTF(err.c_str());
}
