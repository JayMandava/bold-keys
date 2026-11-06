// Official Moonshine Android SDK file-input comparator; no microphone.
#include "moonshine-c-api.h"
#include <algorithm>
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <sys/resource.h>
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point t) { return std::chrono::duration<double,std::milli>(Clock::now()-t).count(); }
int main(int argc, char **argv) {
    if (argc==2 && std::string(argv[1])=="catalog") {
        moonshine_option_t option{"model_arch","2"}; char *json=nullptr;
        int result=moonshine_get_stt_dependencies("en",&option,1,&json);
        if(json){std::cout<<json<<'\n';moonshine_free_buffer(json);}
        return result;
    }
    if(argc!=6) { std::cerr<<"usage: moonshine MODEL_DIR MANIFEST cold|warm REPEATS LABEL\n";return 2; }
    bool warm=std::string(argv[3])=="warm";
    int repeats=std::stoi(argv[4]); if(repeats<1||repeats>20)return 2;
    int32_t handle=-1; double initialLoad=0;
    auto load=[&](){return moonshine_load_transcriber_from_files(argv[1],MOONSHINE_MODEL_ARCH_TINY_STREAMING,nullptr,0,MOONSHINE_HEADER_VERSION);};
    if(warm){auto t=Clock::now();handle=load();initialLoad=ms(t);if(handle<0)return 3;}
    struct Fixture{std::string id,path,language;}; std::vector<Fixture> fixtures;
    std::ifstream manifest(argv[2]);std::string line;
    while(std::getline(manifest,line)){std::istringstream row(line);Fixture f;if(row>>f.id>>f.path>>f.language)fixtures.push_back(f);}
    if(fixtures.empty())return 4;
    std::cout<<"label\tmode\tthreads\trepeat\tid\tload_ms\tinfer_ms\ttotal_ms\tpss_kb\tmaxrss_kb\tstatus\ttext\n";
    for(int r=0;r<repeats;++r)for(const auto &f:fixtures){
        std::ifstream file(f.path,std::ios::binary|std::ios::ate);
        if(!file||file.tellg()<=0||file.tellg()>30*16000*2)return 4;
        size_t bytes=file.tellg();if(bytes%2)return 4;std::vector<int16_t> pcm(bytes/2);
        file.seekg(0);file.read(reinterpret_cast<char*>(pcm.data()),bytes);if(!file)return 4;
        std::vector<float> audio(pcm.size());for(size_t i=0;i<pcm.size();++i)audio[i]=pcm[i]/32768.f;
        auto total=Clock::now();double loading=0;
        if(!warm){auto t=Clock::now();handle=load();loading=ms(t);if(handle<0)return 3;}
        transcript_t *transcript=nullptr;auto t=Clock::now();
        int result=moonshine_transcribe_without_streaming(handle,audio.data(),audio.size(),16000,0,&transcript);
        double inference=ms(t);std::string text;
        if(!result&&transcript)for(uint64_t i=0;i<transcript->line_count;++i){if(i)text+=' ';text+=transcript->lines[i].text;}
        for(char &c:text)if(c=='\t'||c=='\r'||c=='\n')c=' ';
        long pss=-1;std::ifstream memory("/proc/self/smaps_rollup");
        while(std::getline(memory,line))if(line.rfind("Pss:",0)==0){pss=std::stol(line.substr(4));break;}
        rusage usage{};getrusage(RUSAGE_SELF,&usage);
        if(!warm){moonshine_free_transcriber(handle);handle=-1;}
        std::cout<<argv[5]<<'\t'<<argv[3]<<"\tdefault\t"<<r<<'\t'<<f.id<<'\t'<<loading<<'\t'<<inference<<'\t'<<ms(total)<<'\t'<<pss<<'\t'<<usage.ru_maxrss<<'\t'<<result<<'\t'<<(text.empty()?"\"\"":text)<<std::endl;
        std::fill(pcm.begin(),pcm.end(),0);std::fill(audio.begin(),audio.end(),0.f);
    }
    if(handle>=0)moonshine_free_transcriber(handle);
    std::cerr<<"warm_initial_load_ms="<<initialLoad<<'\n';
}
