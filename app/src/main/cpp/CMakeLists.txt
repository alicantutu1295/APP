cmake_minimum_required(VERSION 3.22.1)

project("luminaengine")

# Use the opencvDir passed from Gradle
set(OpenCV_DIR ${opencvDir})
find_package(OpenCV REQUIRED)

add_library(
    luminaengine
    SHARED
    lumina-native.cpp
)

target_link_libraries(
    luminaengine
    android
    log
    jnigraphics
    ${OpenCV_LIBS}
)
