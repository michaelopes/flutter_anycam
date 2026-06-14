cmake_minimum_required(VERSION 3.18.1)
project("flutter_anycam_native_utils")

add_library(flutter_anycam_native_utils SHARED flutter_anycam_native_utils.cpp)
add_library(flutter_anycam_webrtc_yuv_utils SHARED flutter_anycam_webrtc_yuv_utils.cpp)

find_library(log-lib log)

target_link_libraries(flutter_anycam_native_utils ${log-lib})
target_link_libraries(flutter_anycam_webrtc_yuv_utils ${log-lib})