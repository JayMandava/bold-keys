cmake_minimum_required(VERSION 3.22)
project(contextlm CXX C)

# llama.cpp is a submodule (third_party/llama.cpp). Override with -DLLAMA_DIR to use another checkout.
if(NOT LLAMA_DIR)
    set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama)

add_library(contextlm SHARED contextlm.cpp)
target_include_directories(contextlm PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include)
target_link_libraries(contextlm PRIVATE llama ggml log android)

# Separate CMake scope: Whisper and llama pin different ggml APIs. Statically link each
# implementation and export only the voice JNI entry points, preventing symbol interposition.
include(ExternalProject)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
set(VOICE_DEPS ${CMAKE_CURRENT_BINARY_DIR}/voice-deps)
ExternalProject_Add(whisper_deps
    SOURCE_DIR ${WHISPER_DIR}
    BINARY_DIR ${VOICE_DEPS}
    CMAKE_ARGS -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
        -DGGML_LLAMAFILE=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
        -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target whisper --parallel 2
    BUILD_ALWAYS TRUE
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS ${VOICE_DEPS}/src/libwhisper.a ${VOICE_DEPS}/ggml/src/libggml.a
        ${VOICE_DEPS}/ggml/src/libggml-base.a ${VOICE_DEPS}/ggml/src/libggml-cpu.a)
add_library(voice SHARED voice.cpp)
add_dependencies(voice whisper_deps)
target_include_directories(voice PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(voice PRIVATE
    -Wl,--start-group ${VOICE_DEPS}/src/libwhisper.a ${VOICE_DEPS}/ggml/src/libggml.a
    ${VOICE_DEPS}/ggml/src/libggml-cpu.a ${VOICE_DEPS}/ggml/src/libggml-base.a
    -Wl,--end-group log android)
target_link_options(voice PRIVATE -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/voice.exports)
set_property(TARGET voice APPEND PROPERTY LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/voice.exports)
