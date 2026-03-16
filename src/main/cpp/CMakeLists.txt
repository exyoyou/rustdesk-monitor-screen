# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.

cmake_minimum_required(VERSION 3.22.1)
project("yoyo")

add_library(${CMAKE_PROJECT_NAME} SHARED
        yoyo.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
