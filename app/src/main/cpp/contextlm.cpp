// Scores how likely each candidate word is as the next word after some left context. Nothing is generated.
#include <jni.h>
#include <cmath>
#include <string>
#include <vector>

#include "llama.h"

namespace {

struct Lm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int nv = 0;
};

std::vector<llama_token> tok(const llama_vocab *v, const std::string &s, bool addBos) {
    int n = -llama_tokenize(v, s.c_str(), (int) s.size(), nullptr, 0, addBos, true);
    std::vector<llama_token> t(n > 0 ? n : 0);
    if (n <= 0 || llama_tokenize(v, s.c_str(), (int) s.size(), t.data(), n, addBos, true) < 0) t.clear();
    return t;
}

float logp(const float *logits, int n, llama_token t) {
    float mx = logits[0];
    for (int i = 1; i < n; i++) if (logits[i] > mx) mx = logits[i];
    double s = 0;
    for (int i = 0; i < n; i++) s += std::exp(logits[i] - mx);
    return (logits[t] - mx) - (float) std::log(s);
}

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_boldkeys_ContextLm_nativeLoad(JNIEnv *env, jobject, jstring path, jint threads) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    llama_model *m = llama_model_load_from_file(str(env, path).c_str(), mp);
    if (!m) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 256; cp.n_batch = 256; cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context *c = llama_init_from_model(m, cp);
    if (!c) { llama_model_free(m); return 0; }
    Lm *lm = new Lm();
    lm->model = m; lm->ctx = c; lm->vocab = llama_model_get_vocab(m); lm->nv = llama_vocab_n_tokens(lm->vocab);
    return (jlong) lm;
}

JNIEXPORT void JNICALL Java_ai_boldkeys_ContextLm_nativeFree(JNIEnv *, jobject, jlong h) {
    Lm *lm = (Lm *) h;
    if (!lm) return;
    llama_free(lm->ctx); llama_model_free(lm->model); delete lm;
}

// Sum of token log-probabilities of each candidate following [left]; -99 when a candidate cannot be scored.
JNIEXPORT jfloatArray JNICALL Java_ai_boldkeys_ContextLm_nativeScore(JNIEnv *env, jobject, jlong h, jstring left, jobjectArray cands) {
    Lm *lm = (Lm *) h;
    int nc = env->GetArrayLength(cands);
    std::vector<float> out(nc, -99.f);
    std::string ctxText = str(env, left);
    auto L = tok(lm->vocab, ctxText, true);
    int n = (int) L.size();
    if (lm->model && n > 0 && n < 200) {
        llama_memory_clear(llama_get_memory(lm->ctx), false);
        llama_batch lb = llama_batch_get_one(L.data(), n);
        if (llama_decode(lm->ctx, lb) == 0) {
            std::vector<float> last(llama_get_logits_ith(lm->ctx, -1), llama_get_logits_ith(lm->ctx, -1) + lm->nv);
            for (int c = 0; c < nc; c++) {
                std::string cand = str(env, (jstring) env->GetObjectArrayElement(cands, c));
                auto full = tok(lm->vocab, ctxText + " " + cand, true);
                bool prefix = (int) full.size() > n;
                for (int i = 0; prefix && i < n; i++) if (full[i] != L[i]) prefix = false;
                if (!prefix) continue;
                std::vector<llama_token> C(full.begin() + n, full.end());
                int k = (int) C.size();
                double sum = logp(last.data(), lm->nv, C[0]);
                if (k > 1) {
                    llama_batch b = llama_batch_init(k, 0, 1);
                    for (int i = 0; i < k - 1; i++) { b.token[i] = C[i]; b.pos[i] = n + i; b.n_seq_id[i] = 1; b.seq_id[i][0] = 0; b.logits[i] = 1; }
                    b.n_tokens = k - 1;
                    if (llama_decode(lm->ctx, b) == 0)
                        for (int i = 0; i < k - 1; i++) sum += logp(llama_get_logits_ith(lm->ctx, i), lm->nv, C[i + 1]);
                    llama_batch_free(b);
                    llama_memory_seq_rm(llama_get_memory(lm->ctx), 0, n, -1);
                }
                out[c] = (float) sum;
            }
        }
    }
    jfloatArray r = env->NewFloatArray(nc);
    env->SetFloatArrayRegion(r, 0, nc, out.data());
    return r;
}

}
