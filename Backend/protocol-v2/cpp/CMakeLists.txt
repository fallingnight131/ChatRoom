cmake_minimum_required(VERSION 3.25)

project(chat_room_v2_cpp_binding_test LANGUAGES CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
FetchContent_Declare(
  absl
  URL https://github.com/abseil/abseil-cpp/releases/download/20250512.1/abseil-cpp-20250512.1.tar.gz
  URL_HASH SHA256=9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(absl)

set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_PROTOC_BINARIES OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBPROTOC OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBUPB OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(protobuf_INSTALL OFF CACHE BOOL "" FORCE)
set(protobuf_FORCE_FETCH_DEPENDENCIES ON CACHE BOOL "" FORCE)

FetchContent_Declare(
  protobuf
  URL https://github.com/protocolbuffers/protobuf/releases/download/v35.1/protobuf-35.1.tar.gz
  URL_HASH SHA256=f0b6838e7522a8da96126d487068c959bc624926368f3024ac8fd03abd0a1ac4
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(protobuf)

set(GENERATED_ROOT "${CMAKE_CURRENT_LIST_DIR}/../typescript/generated/cpp")

add_executable(
  v2_cpp_envelope_test
  EnvelopeCompatibilityTest.cpp
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
target_compile_features(v2_cpp_envelope_test PRIVATE cxx_std_17)
target_include_directories(v2_cpp_envelope_test PRIVATE "${GENERATED_ROOT}")
target_link_libraries(v2_cpp_envelope_test PRIVATE protobuf::libprotobuf)

if(MSVC)
  target_compile_options(v2_cpp_envelope_test PRIVATE /W4 /WX)
else()
  # Keep project warnings fatal without enabling pedantic diagnostics for
  # intentional compiler extensions in Protobuf/Abseil public headers.
  target_compile_options(v2_cpp_envelope_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    # Abseil intentionally exposes Clang nullability annotations in public
    # headers; they are extensions, not warnings in this project's sources.
    target_compile_options(v2_cpp_envelope_test PRIVATE -Wno-nullability-extension)
  endif()
endif()

enable_testing()
add_test(NAME v2_cpp_envelope_golden COMMAND v2_cpp_envelope_test)
