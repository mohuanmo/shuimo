# glasskey-core：C++17 平台无关核心引擎（§3.2）
cmake_minimum_required(VERSION 3.16)
project(glasskey_core CXX)

file(GLOB_RECURSE GK_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp)
list(FILTER GK_SOURCES EXCLUDE REGEX ".*/gk_c_api\\.cpp$")  # C ABI 仅 Android JNI 侧编译

add_library(glasskey_core STATIC ${GK_SOURCES})
target_include_directories(glasskey_core
  PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_features(glasskey_core PUBLIC cxx_std_17)
if(MSVC)
  target_compile_options(glasskey_core PRIVATE /W4 /utf-8)
else()
  target_compile_options(glasskey_core PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

# 宿主单元测试（§10.4：音节切分全表/T9 展开/DAT 一致性/解码 golden/用户模型）
if(GK_BUILD_TESTS)
  enable_testing()
  file(GLOB GK_TESTS CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/tests/*.cpp)
  add_executable(gk_tests ${GK_TESTS} src/gk_c_api.cpp)  # 宿主测试同时验证 C ABI
  target_link_libraries(gk_tests PRIVATE glasskey_core)
  target_compile_features(gk_tests PRIVATE cxx_std_17)
  add_test(NAME gk_tests COMMAND gk_tests
           WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/tests)
endif()
