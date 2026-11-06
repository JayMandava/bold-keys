// File-input CPU benchmark. Never opens a microphone or an editor.
// Build against the same pinned whisper.cpp libraries as libvoice.
#include <whisper.h>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <sys/resource.h>

using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
static void quiet(ggml_log_level, const char *, void *) {}
static long pss() {
    std::ifstream input("/proc/self/smaps_rollup");
    std::string line;
    while (std::getline(input, line)) {
        if (line.rfind("Pss:", 0) == 0) return std::stol(line.substr(4));
    }
    return -1;
}
int main(int argc, char **argv) {
    if (argc != 7 && argc != 8) {
        std::cerr << "usage: benchmark MODEL MANIFEST cold|warm THREADS REPEATS LABEL [AUDIO_CTX]\n";
        return 2;
    }
    whisper_log_set(quiet, nullptr);
    const bool warm = std::string(argv[3]) == "warm";
    const int threads = std::stoi(argv[4]), repeats = std::stoi(argv[5]);
    if (threads < 1 || threads > 8 || repeats < 1 || repeats > 20) return 2;
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = nullptr;
    double firstLoad = 0;
    if (warm) {
        auto start = Clock::now();
        ctx = whisper_init_from_file_with_params(argv[1], cp);
        firstLoad = ms(start);
        if (!ctx) return 3;
    }
    std::ifstream manifest(argv[2]);
    if (!manifest) return 4;
    struct Fixture { std::string id, path, language; };
    std::vector<Fixture> fixtures;
    std::string line;
    while (std::getline(manifest, line)) {
        std::istringstream row(line);
        Fixture f;
        if (row >> f.id >> f.path >> f.language) fixtures.push_back(f);
    }
    if (fixtures.empty()) return 4;
    std::cout << "label\tmode\tthreads\trepeat\tid\tload_ms\tinfer_ms\ttotal_ms\tpss_kb\tmaxrss_kb\tstatus\ttext\n";
    for (int repeat = 0; repeat < repeats; ++repeat) for (const auto &f : fixtures) {
        std::ifstream file(f.path, std::ios::binary | std::ios::ate);
        if (!file || file.tellg() <= 0 || file.tellg() > 30*16000*2) return 4;
        auto bytes = static_cast<size_t>(file.tellg());
        if (bytes % 2) return 4;
        std::vector<int16_t> pcm(bytes/2);
        file.seekg(0); file.read(reinterpret_cast<char *>(pcm.data()), bytes);
        if (!file) return 4;
        std::vector<float> audio(pcm.size());
        for (size_t i=0;i<pcm.size();++i) audio[i] = pcm[i]/32768.f;
        auto totalStart = Clock::now();
        double load = 0;
        if (!warm) {
            auto start = Clock::now();
            ctx = whisper_init_from_file_with_params(argv[1], cp);
            load = ms(start);
            if (!ctx) return 3;
        }
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = threads;
        if (argc == 8) {
            int requested = std::stoi(argv[7]);
            params.audio_ctx = requested == -1
                ? (f.language == "en" ? std::min(1500, std::max(512, (int(audio.size()) + 319) / 320 + 150)) : 0)
                : requested;
        }
        params.language = f.language.c_str();
        params.translate = false; params.no_context = true; params.single_segment = true;
        params.suppress_blank = true; params.suppress_nst = true;
        params.print_progress = params.print_realtime = params.print_timestamps = params.print_special = false;
        auto start = Clock::now();
        int result = whisper_full(ctx, params, audio.data(), audio.size());
        double infer = ms(start);
        std::string text;
        if (!result) for (int i=0;i<whisper_full_n_segments(ctx);++i) text += whisper_full_get_segment_text(ctx,i);
        for (char &c : text) if (c=='\t'||c=='\r'||c=='\n') c=' ';
        rusage usage{}; getrusage(RUSAGE_SELF, &usage);
        const auto memory = pss();
        if (!warm) { whisper_free(ctx); ctx=nullptr; }
        double total = ms(totalStart);
        std::cout << argv[6] << '\t' << argv[3] << '\t' << threads << '\t' << repeat << '\t' << f.id
                  << '\t' << load << '\t' << infer << '\t' << total << '\t' << memory
                  << '\t' << usage.ru_maxrss << '\t' << result << '\t' << (text.empty() ? "\"\"" : text) << std::endl;
        std::fill(pcm.begin(),pcm.end(),0); std::fill(audio.begin(),audio.end(),0.f);
    }
    if(ctx) whisper_free(ctx);
    std::cerr << "warm_initial_load_ms=" << firstLoad << '\n';
}
