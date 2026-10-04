# The Qt 6 host of the xnotes core. The core is libxnotes.so, built by Gradle:
#   ./gradlew -PdesktopOnly=true :capi:linkXnotesReleaseSharedLinuxX64
# then
#   cmake -S linux-qt -B linux-qt/build && cmake --build linux-qt/build && ctest --test-dir linux-qt/build
cmake_minimum_required(VERSION 3.21)
project(xnotes_qt LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

find_package(Qt6 6.4 REQUIRED COMPONENTS Core Gui Widgets OpenGL OpenGLWidgets Pdf Test)
find_package(ZLIB REQUIRED)

set(XNOTES_CAPI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../capi" CACHE PATH "The capi module (header and Gradle build output)")
set(XNOTES_LIB "${XNOTES_CAPI_DIR}/build/bin/linuxX64/xnotesReleaseShared/libxnotes.so" CACHE FILEPATH "libxnotes.so")
if(NOT EXISTS "${XNOTES_LIB}")
    message(FATAL_ERROR "${XNOTES_LIB} not found: build it with ./gradlew -PdesktopOnly=true :capi:linkXnotesReleaseSharedLinuxX64")
endif()

# The core, copied beside the executables so they find it through $ORIGIN.
set(XNOTES_LIB_COPY "${CMAKE_CURRENT_BINARY_DIR}/libxnotes.so")
add_custom_command(OUTPUT "${XNOTES_LIB_COPY}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${XNOTES_LIB}" "${XNOTES_LIB_COPY}"
    DEPENDS "${XNOTES_LIB}")
add_custom_target(xnotes_core_copy DEPENDS "${XNOTES_LIB_COPY}")
add_library(xnotes_core SHARED IMPORTED)
set_target_properties(xnotes_core PROPERTIES
    IMPORTED_LOCATION "${XNOTES_LIB_COPY}"
    IMPORTED_NO_SONAME ON
    INTERFACE_INCLUDE_DIRECTORIES "${XNOTES_CAPI_DIR}/include")
add_dependencies(xnotes_core xnotes_core_copy)

set(CMAKE_BUILD_RPATH "\$ORIGIN")
set(CMAKE_INSTALL_RPATH "\$ORIGIN")

# Everything but main(), shared by the app and the smoke test.
add_library(xnotes_host STATIC
    src/CoreHost.cpp src/CoreHost.h
    src/CanvasWidget.cpp src/CanvasWidget.h
    src/MainWindow.cpp src/MainWindow.h
    src/SelectionBar.cpp src/SelectionBar.h
    src/TextOverlay.cpp src/TextOverlay.h
    src/PdfExport.cpp src/PdfExport.h
    src/NoteFinder.cpp src/NoteFinder.h)
target_include_directories(xnotes_host PUBLIC src)
target_link_libraries(xnotes_host PUBLIC Qt6::Widgets Qt6::OpenGLWidgets Qt6::Pdf ZLIB::ZLIB xnotes_core)
target_compile_options(xnotes_host PRIVATE -Wall -Wextra)

add_executable(xnotes-qt src/main.cpp)
target_link_libraries(xnotes-qt PRIVATE xnotes_host)

enable_testing()
add_executable(xnotes_qt_smoke test/smoke_test.cpp)
target_link_libraries(xnotes_qt_smoke PRIVATE xnotes_host Qt6::Test Qt6::OpenGL ZLIB::ZLIB)
# Both ways a frame can reach the screen.
add_test(NAME smoke_gl COMMAND xnotes_qt_smoke)
set_tests_properties(smoke_gl PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;XNOTES_RENDER=gl")
add_test(NAME smoke_raster COMMAND xnotes_qt_smoke)
set_tests_properties(smoke_raster PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen;XNOTES_RENDER=raster")
