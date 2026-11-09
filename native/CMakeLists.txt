cmake_minimum_required(VERSION 3.24)
project(veyra_session VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(WIN32)
    add_library(veyra-session SHARED windows/MediaSession.cpp)
    target_compile_definitions(veyra-session PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
    target_compile_options(veyra-session PRIVATE /W4 /permissive- /EHsc)
    target_link_libraries(veyra-session PRIVATE windowsapp ole32)
    # A clean Windows installation must not need a separate MSVC runtime installer.
    set_property(TARGET veyra-session PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
elseif(APPLE)
    enable_language(OBJCXX)
    add_library(veyra-session SHARED macos/MediaSession.mm)
    target_compile_options(veyra-session PRIVATE -fobjc-arc -Wall -Wextra)
    target_link_libraries(veyra-session PRIVATE "-framework AppKit" "-framework Foundation" "-framework MediaPlayer" "-framework CoreAudio")
    set_target_properties(veyra-session PROPERTIES MACOSX_RPATH ON)
else()
    message(STATUS "Linux uses the packaged dbus-java MPRIS adapter; no native session helper")
    return()
endif()

target_include_directories(veyra-session PRIVATE include)
set_target_properties(veyra-session PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output")
foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set_target_properties(veyra-session PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY_${config} "${CMAKE_BINARY_DIR}/output"
        RUNTIME_OUTPUT_DIRECTORY_${config} "${CMAKE_BINARY_DIR}/output")
endforeach()
install(TARGETS veyra-session LIBRARY DESTINATION . RUNTIME DESTINATION .)
