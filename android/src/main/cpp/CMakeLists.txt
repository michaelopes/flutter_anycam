cmake_minimum_required(VERSION 3.18.1)
project("yuv_utils")

add_library(yuv_utils SHARED yuv_utils.cpp)

find_library(log-lib log)

target_link_libraries(yuv_utils ${log-lib})