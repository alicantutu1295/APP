cmake_minimum_required(VERSION 3.22.1)

project("luminaengine")

# Use the opencvDir passed from Gradle (if provided)
set(OpenCV_DIR ${opencvDir})

# Try to find OpenCV, but make it optional
find_package(OpenCV QUIET)

add_library(
    luminaengine
    SHARED
    lumina-native.cpp
)

# Base libraries always linked
target_link_libraries(
    luminaengine
    android
    log
    jnigraphics
)

# Only link OpenCV if found
if(OpenCV_FOUND)
    target_link_libraries(luminaengine ${OpenCV_LIBS})
    target_compile_definitions(luminaengine PRIVATE OPENCV_AVAILABLE)
endif()
