#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <algorithm>
#include <string>
#include <vector>

struct VoiceModel {
    whisper_context *ctx;
    std::atomic<bool> cancel{false};
    explicit VoiceModel(whisper_context *c): ctx(c) {}
    ~VoiceModel() { whisper_free(ctx); }
};
static void quiet(enum ggml_log_level, const char *, void *) {}
extern "C" JNIEXPORT jlong JNICALL
Java_ai_boldkeys_Whisper_nativeLoad(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(quiet, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(p, params);
    env->ReleaseStringUTFChars(path, p);
    return ctx ? reinterpret_cast<jlong>(new VoiceModel(ctx)) : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_ai_boldkeys_Whisper_nativeCancel(JNIEnv *, jobject, jlong h) {
    reinterpret_cast<VoiceModel *>(h)->cancel.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_ai_boldkeys_Whisper_nativeFree(JNIEnv *, jobject, jlong h) {
    delete reinterpret_cast<VoiceModel *>(h);
}
extern "C" JNIEXPORT jstring JNICALL
Java_ai_boldkeys_Whisper_nativeTranscribe(JNIEnv *env, jobject, jlong h, jfloatArray audio, jstring language) {
    auto *model = reinterpret_cast<VoiceModel *>(h);
    const auto n = env->GetArrayLength(audio);
    if (n < 1600 || n > 30 * 16000 || model->cancel.load()) return nullptr;
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(audio, 0, n, pcm.data());
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (!lang) return nullptr;
    std::string langCopy(lang);
    env->ReleaseStringUTFChars(language, lang);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 2;
    // English short dictation need not encode 30 seconds of padding. Keep three
    // seconds of headroom, grow with the entire recording, and retain the full
    // context for other languages (short contexts regressed German in testing).
    if (langCopy == "en") params.audio_ctx = std::min(1500, std::max(512, (n + 319) / 320 + 150));
    params.language = langCopy.c_str();
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.print_progress = params.print_realtime = params.print_timestamps = params.print_special = false;
    params.abort_callback = [](void *p) { return static_cast<VoiceModel *>(p)->cancel.load(); };
    params.abort_callback_user_data = model;
    const int result = whisper_full(model->ctx, params, pcm.data(), n);
    std::fill(pcm.begin(), pcm.end(), 0.f);
    if (result != 0 || model->cancel.load()) return nullptr;
    std::string out;
    for (int i = 0; i < whisper_full_n_segments(model->ctx); ++i) out += whisper_full_get_segment_text(model->ctx, i);
    // Whisper emits UTF-8, while NewStringUTF expects modified UTF-8. Decode explicitly in Kotlin.
    auto bytes = env->NewByteArray(out.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, out.size(), reinterpret_cast<const jbyte *>(out.data()));
    auto cls = env->FindClass("java/lang/String");
    auto ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    return static_cast<jstring>(env->NewObject(cls, ctor, bytes, env->NewStringUTF("UTF-8")));
}
