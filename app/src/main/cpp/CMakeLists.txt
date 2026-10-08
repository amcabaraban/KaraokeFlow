cmake_minimum_required(VERSION 3.22.1)
project(karaokeflow_synth LANGUAGES CXX)

add_library(karaokeflow_synth SHARED karaokeflow_synth.cpp)
target_compile_features(karaokeflow_synth PRIVATE cxx_std_17)
target_compile_options(karaokeflow_synth PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(karaokeflow_synth PRIVATE m)
