cmake_minimum_required(VERSION 3.22.1)
project(Demo)

set(APP_NAME Demo)

get_filename_component(LIVE2D_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(NATIVE_DIR "${LIVE2D_DIR}/sdk/cubism-sdk-native")
set(SDK_ROOT_PATH "${LIVE2D_DIR}/sdk/cubism-sdk-motionsync")
set(NATIVE_PATH "${NATIVE_DIR}" CACHE PATH "Cubism Native SDK" FORCE)

set(NATIVE_CORE_PATH "${NATIVE_DIR}/Core")
set(NATIVE_FRAMEWORK_PATH "${NATIVE_DIR}/Framework")
set(MOTIONSYNC_FRAMEWORK_PATH "${SDK_ROOT_PATH}/Framework")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore
  PROPERTIES
    IMPORTED_LOCATION "${NATIVE_CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a"
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_CORE_PATH}/include"
)

set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory("${NATIVE_FRAMEWORK_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/Framework")
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)

add_subdirectory("${MOTIONSYNC_FRAMEWORK_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/MotionSyncFramework")
target_compile_definitions(MotionSyncFramework PRIVATE MOTIONSYNC_CORE_CRI_LIB_PATH="")

add_library(${APP_NAME} SHARED
  JniBridgeC.cpp
  LAppMotionSyncLinkModel.cpp
  LAppAudioManager.cpp
  LAppMotionSyncLinkView.cpp
  LAppMotionSyncLinkDelegate.cpp
  LAppMotionSyncDefine.cpp
  LAppAllocator_Common.cpp
  LAppWavFileHandler_Common.cpp
  LAppTextureManager_Common.cpp
  LAppDefine.cpp
  LAppPal.cpp
  LAppTextureManager.cpp
)

target_include_directories(${APP_NAME} PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}"
  "${CMAKE_CURRENT_SOURCE_DIR}/stb"
)

target_link_libraries(${APP_NAME}
  Framework
  MotionSyncFramework
  Live2DCubismCore
  GLESv2
  log
)

target_link_options(${APP_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
