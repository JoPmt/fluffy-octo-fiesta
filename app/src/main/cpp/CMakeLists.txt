cmake_minimum_required(VERSION 3.22.1)
project("llama_agent")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8-a+simd+dotprod)
    add_definitions(-DGGML_USE_NEON)
    add_definitions(-DGGML_USE_CPU_HAVE_DOTPROD)
endif()

set(CMAKE_CXX_FLAGS_RELEASE "-O3 -Ofast -fomit-frame-pointer -flto")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Disable llama-common" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable llama tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable llama examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable llama server" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Disable llama tools" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "Disable llama app" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "Disable HTTPS support" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Disable native codegen" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "Enable backend loader" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "Enable all CPU variants" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile integration" FORCE)

add_subdirectory(llama.cpp)

add_library(${PROJECT_NAME} SHARED
    jni_mapping_bridge.cpp
    llama_agent_core.cpp
    grammar_rules.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(${PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/src
)

target_link_libraries(${PROJECT_NAME}
    PRIVATE
    llama
    ${log-lib}
    ${android-lib}
)
