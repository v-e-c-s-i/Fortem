cmake_minimum_required(VERSION 3.22.1)

project(fortem_audio LANGUAGES CXX)

# Native-библиотека содержит только звуковой тракт; интерфейс остаётся в Kotlin.
add_library(
    fortem-audio
    SHARED
    native_audio.cpp
    piano_sample_bank.cpp
)

find_package(oboe REQUIRED CONFIG)

target_compile_features(fortem-audio PRIVATE cxx_std_20)
target_compile_options(fortem-audio PRIVATE -Wall -Wextra -Wpedantic)

target_link_libraries(
    fortem-audio
    PRIVATE
    oboe::oboe
    android
    log
)
