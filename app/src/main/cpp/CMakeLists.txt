cmake_minimum_required(VERSION 3.4.2)

set (CMAKE_CXX_STANDARD 11)

add_library(adb SHARED adb.cpp)

find_library(logger log)

target_link_libraries(adb ${logger})