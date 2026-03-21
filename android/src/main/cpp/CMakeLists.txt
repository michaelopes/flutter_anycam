cmake_minimum_required(VERSION 3.18.1)
project("flutter_anycam_yuv_utils")

add_library(flutter_anycam_yuv_utils SHARED flutter_anycam_yuv_utils.cpp)

find_library(log-lib log)

target_link_libraries(flutter_anycam_yuv_utils ${log-lib})