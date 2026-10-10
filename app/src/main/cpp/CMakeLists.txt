cmake_minimum_required(VERSION 3.22)
project(contextlm CXX C)

# llama.cpp is not vendored: point LLAMA_DIR at a checkout (default: the one next to Nano Search).
if(NOT LLAMA_DIR)
    set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../nanosearch/llama.cpp)
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
