cmake_minimum_required(VERSION 3.4.2)

set (CMAKE_CXX_STANDARD 11)

include_directories(include boringssl/include libcrypto_utils/include)

find_library(logger log)

set(EXTRA_LIBS ${logger})

set(SOURCES adb.cpp auth.cpp libcrypto_utils/android_pubkey.c)

add_library(crypto STATIC IMPORTED)

set_property(TARGET crypto PROPERTY IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/boringssl/libcrypto.a)

set(EXTRA_LIBS ${EXTRA_LIBS} crypto)

add_library(adb SHARED ${SOURCES})

target_link_libraries(adb ${EXTRA_LIBS})