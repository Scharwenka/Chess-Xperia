cmake_minimum_required(VERSION 3.22.1)
project(xperiamix LANGUAGES CXX)

# Oboe est fourni sous forme d'AAR "prefab" (android.buildFeatures.prefab = true).
find_package(oboe REQUIRED CONFIG)

add_library(xperiamix SHARED
        MixerEngine.cpp
        jni_bridge.cpp)

target_compile_options(xperiamix PRIVATE
        -Wall -Wextra -Wshadow
        -ffast-math                 # sur le chemin de mix uniquement : pas de NaN attendu
        -fno-omit-frame-pointer)    # profils simpleperf exploitables

target_link_libraries(xperiamix PRIVATE
        oboe::oboe
        android
        log)
