# GlassKey Android 原生构建：core（C++17 引擎）+ JNI 桥 → libglasskey.so
cmake_minimum_required(VERSION 3.22)
project(glasskey_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(GK_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../core)  # cpp → main → src → app → GlassKey/core

add_library(glasskey SHARED
        gk_jni.cpp
        ${GK_CORE}/src/gk_c_api.cpp
        ${GK_CORE}/src/engine.cpp
        ${GK_CORE}/src/common/crc32.cpp
        ${GK_CORE}/src/common/mapped_file.cpp
        ${GK_CORE}/src/dict/dict_store.cpp
        ${GK_CORE}/src/pinyin/pinyin_engine.cpp
        ${GK_CORE}/src/pinyin/segment.cpp
        ${GK_CORE}/src/pinyin/syllable_table.cpp
        ${GK_CORE}/src/latin/latin_engine.cpp
        ${GK_CORE}/src/user/user_model.cpp)

target_include_directories(glasskey PRIVATE
        ${GK_CORE}/include
        ${GK_CORE}/src)

target_compile_options(glasskey PRIVATE -O2 -fvisibility=hidden -Wall)
