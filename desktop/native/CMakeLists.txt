# Desktop build of the same native LoRa PHY the Android app uses (JNI).
cmake_minimum_required(VERSION 3.16)
project(meshsdr_lora LANGUAGES CXX)
# Windows: static C++ runtime, so the DLL needs nothing beyond the bundled JVM.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
find_package(JNI REQUIRED)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
add_library(meshsdr_lora SHARED ${CPP}/lora_jni.cpp)
target_compile_features(meshsdr_lora PRIVATE cxx_std_17)
target_compile_definitions(meshsdr_lora PRIVATE LORA_LITE_MAX_SF=12 LORA_LITE_MAX_PAYLOAD=255 _USE_MATH_DEFINES)
target_include_directories(meshsdr_lora PRIVATE ${CPP} ${CPP}/upstream ${JNI_INCLUDE_DIRS})
if(MSVC)
  target_compile_options(meshsdr_lora PRIVATE /O2)
else()
  target_compile_options(meshsdr_lora PRIVATE -O2)
  # Self-contained .so/.dll: no dependency on a particular libstdc++.
  target_link_options(meshsdr_lora PRIVATE -static-libstdc++ -static-libgcc)
endif()
