cmake_minimum_required(VERSION 3.25)

project(chat_room_v2_cpp_binding_test LANGUAGES CXX)

find_package(Qt6 6.4 REQUIRED COMPONENTS Core Network Sql WebSockets)
include("${CMAKE_CURRENT_LIST_DIR}/../../../cmake/ChatRoomV2Protobuf.cmake")
chatroom_configure_v2_protobuf()

set(GENERATED_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../Client/protocol/v2/generated")

add_executable(
  v2_cpp_envelope_test
  EnvelopeCompatibilityTest.cpp
  "${GENERATED_ROOT}/chat/v2/attachment.pb.cc"
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/conversation.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
  "${GENERATED_ROOT}/chat/v2/messaging.pb.cc"
)
target_compile_features(v2_cpp_envelope_test PRIVATE cxx_std_17)
target_include_directories(v2_cpp_envelope_test PRIVATE "${GENERATED_ROOT}")
target_link_libraries(v2_cpp_envelope_test PRIVATE protobuf::libprotobuf)

add_executable(
  v2_windows_device_management_protocol_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2DeviceManagementProtocolClientTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2DeviceManagementProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
target_compile_features(v2_windows_device_management_protocol_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_device_management_protocol_test
  PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../Client"
    "${GENERATED_ROOT}"
)
target_link_libraries(
  v2_windows_device_management_protocol_test
  PRIVATE protobuf::libprotobuf
)

add_executable(
  v2_windows_messaging_protocol_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsMessagingProtocolClientTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
  "${GENERATED_ROOT}/chat/v2/messaging.pb.cc"
)
target_compile_features(v2_windows_messaging_protocol_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_messaging_protocol_test
  PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../Client"
    "${GENERATED_ROOT}"
)
target_link_libraries(v2_windows_messaging_protocol_test PRIVATE protobuf::libprotobuf)

add_executable(
  v2_windows_conversation_directory_protocol_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsConversationDirectoryProtocolClientTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationDirectoryProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/conversation.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
target_compile_features(v2_windows_conversation_directory_protocol_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_conversation_directory_protocol_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client" "${GENERATED_ROOT}")
target_link_libraries(
  v2_windows_conversation_directory_protocol_test PRIVATE protobuf::libprotobuf)

add_executable(
  v2_windows_conversation_participant_protocol_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsConversationParticipantProtocolClientTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/conversation.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
target_compile_features(v2_windows_conversation_participant_protocol_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_conversation_participant_protocol_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client" "${GENERATED_ROOT}")
target_link_libraries(
  v2_windows_conversation_participant_protocol_test PRIVATE protobuf::libprotobuf)

add_executable(
  v2_windows_conversation_participant_view_model_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsConversationParticipantViewModelTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantViewModel.h"
)
set_target_properties(v2_windows_conversation_participant_view_model_test PROPERTIES AUTOMOC ON)
target_compile_features(v2_windows_conversation_participant_view_model_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_conversation_participant_view_model_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client")
target_link_libraries(v2_windows_conversation_participant_view_model_test PRIVATE Qt6::Core)

add_executable(
  v2_windows_mention_composer_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsMentionComposerTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMentionComposer.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMentionComposer.h"
)
target_compile_features(v2_windows_mention_composer_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_mention_composer_test PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client")
target_link_libraries(v2_windows_mention_composer_test PRIVATE Qt6::Core Qt6::Sql)

add_executable(
  v2_windows_messaging_application_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsMessagingApplicationServiceTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingApplicationService.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2LocalMessageRepository.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
  "${GENERATED_ROOT}/chat/v2/messaging.pb.cc"
)
target_compile_features(v2_windows_messaging_application_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_messaging_application_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client" "${GENERATED_ROOT}")
target_link_libraries(
  v2_windows_messaging_application_test
  PRIVATE Qt6::Core Qt6::Sql protobuf::libprotobuf)

add_executable(
  v2_windows_session_protocol_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsSessionProtocolClientTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsSessionProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2DeviceManagementProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
target_compile_features(v2_windows_session_protocol_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_session_protocol_test
  PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../Client"
    "${GENERATED_ROOT}"
)
target_link_libraries(v2_windows_session_protocol_test PRIVATE protobuf::libprotobuf)

add_executable(
  v2_windows_device_management_transport_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/V2WindowsDeviceManagementTransportTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsDeviceManagementTransport.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsDeviceManagementTransport.h"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsSessionProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2DeviceManagementProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
)
set_target_properties(v2_windows_device_management_transport_test PROPERTIES AUTOMOC ON)
target_compile_features(v2_windows_device_management_transport_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_device_management_transport_test
  PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../Client"
    "${GENERATED_ROOT}"
)
target_link_libraries(
  v2_windows_device_management_transport_test
  PRIVATE Qt6::Core Qt6::Network Qt6::WebSockets protobuf::libprotobuf
)

add_executable(
  v2_windows_device_management_controller_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/WindowsDeviceManagementControllerTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/WindowsDeviceManagementController.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/DeviceManagementApplicationService.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/DeviceManagementViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsDeviceManagementTransport.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsSessionProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2DeviceManagementProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/WindowsV2MessagingController.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationDirectoryProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationDirectoryViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingApplicationService.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2LocalMessageRepository.cpp"
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/conversation.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
  "${GENERATED_ROOT}/chat/v2/messaging.pb.cc"
)
set_target_properties(v2_windows_device_management_controller_test PROPERTIES AUTOMOC ON)
target_compile_features(v2_windows_device_management_controller_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_device_management_controller_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client" "${GENERATED_ROOT}"
)
target_link_libraries(
  v2_windows_device_management_controller_test
  PRIVATE Qt6::Core Qt6::Network Qt6::Sql Qt6::WebSockets protobuf::libprotobuf
)

add_executable(
  v2_windows_messaging_controller_test
  "${CMAKE_CURRENT_LIST_DIR}/../../../Tests/WindowsV2MessagingControllerTest.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/WindowsV2MessagingController.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/WindowsV2MessagingController.h"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationDirectoryProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationDirectoryViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsConversationParticipantViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingApplicationService.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsMessagingViewModel.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2LocalMessageRepository.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsDeviceManagementTransport.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2WindowsSessionProtocolClient.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/../../../Client/V2DeviceManagementProtocolClient.cpp"
  "${GENERATED_ROOT}/chat/v2/authentication.pb.cc"
  "${GENERATED_ROOT}/chat/v2/control.pb.cc"
  "${GENERATED_ROOT}/chat/v2/conversation.pb.cc"
  "${GENERATED_ROOT}/chat/v2/device_management.pb.cc"
  "${GENERATED_ROOT}/chat/v2/envelope.pb.cc"
  "${GENERATED_ROOT}/chat/v2/messaging.pb.cc"
)
set_target_properties(v2_windows_messaging_controller_test PROPERTIES AUTOMOC ON)
target_compile_features(v2_windows_messaging_controller_test PRIVATE cxx_std_17)
target_include_directories(
  v2_windows_messaging_controller_test
  PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../Client" "${GENERATED_ROOT}")
target_link_libraries(
  v2_windows_messaging_controller_test
  PRIVATE Qt6::Core Qt6::Network Qt6::Sql Qt6::WebSockets protobuf::libprotobuf)

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

if(MSVC)
  target_compile_options(v2_windows_device_management_transport_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_device_management_controller_test PRIVATE /W4 /WX)
else()
  target_compile_options(
    v2_windows_device_management_transport_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_device_management_transport_test
      PRIVATE -Wno-nullability-extension
    )
  endif()
  target_compile_options(
    v2_windows_device_management_controller_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_device_management_controller_test
      PRIVATE -Wno-nullability-extension
    )
  endif()
endif()

if(MSVC)
  target_compile_options(v2_windows_session_protocol_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_messaging_controller_test PRIVATE /W4 /WX)
else()
  target_compile_options(v2_windows_session_protocol_test PRIVATE -Wall -Wextra -Werror)
  target_compile_options(v2_windows_messaging_controller_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_session_protocol_test
      PRIVATE -Wno-nullability-extension
    )
    target_compile_options(
      v2_windows_messaging_controller_test
      PRIVATE -Wno-nullability-extension
    )
  endif()
endif()

if(MSVC)
  target_compile_options(v2_windows_device_management_protocol_test PRIVATE /W4 /WX)
else()
  target_compile_options(
    v2_windows_device_management_protocol_test
    PRIVATE -Wall -Wextra -Werror
  )
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_device_management_protocol_test
      PRIVATE -Wno-nullability-extension
    )
  endif()
endif()

if(MSVC)
  target_compile_options(v2_windows_messaging_protocol_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_conversation_directory_protocol_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_conversation_participant_protocol_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_conversation_participant_view_model_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_mention_composer_test PRIVATE /W4 /WX)
  target_compile_options(v2_windows_messaging_application_test PRIVATE /W4 /WX)
else()
  target_compile_options(v2_windows_messaging_protocol_test PRIVATE -Wall -Wextra -Werror)
  target_compile_options(
    v2_windows_conversation_directory_protocol_test PRIVATE -Wall -Wextra -Werror)
  target_compile_options(
    v2_windows_conversation_participant_protocol_test PRIVATE -Wall -Wextra -Werror)
  target_compile_options(
    v2_windows_conversation_participant_view_model_test PRIVATE -Wall -Wextra -Werror)
  target_compile_options(v2_windows_mention_composer_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_messaging_protocol_test PRIVATE -Wno-nullability-extension)
    target_compile_options(
      v2_windows_conversation_directory_protocol_test
      PRIVATE -Wno-nullability-extension)
    target_compile_options(
      v2_windows_conversation_participant_protocol_test
      PRIVATE -Wno-nullability-extension)
  endif()
  target_compile_options(v2_windows_messaging_application_test PRIVATE -Wall -Wextra -Werror)
  if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(
      v2_windows_messaging_application_test PRIVATE -Wno-nullability-extension)
  endif()
endif()

enable_testing()
add_test(NAME v2_cpp_envelope_golden COMMAND v2_cpp_envelope_test)
add_test(
  NAME v2_windows_device_management_protocol
  COMMAND v2_windows_device_management_protocol_test
)
add_test(NAME v2_windows_messaging_protocol COMMAND v2_windows_messaging_protocol_test)
add_test(
  NAME v2_windows_conversation_directory_protocol
  COMMAND v2_windows_conversation_directory_protocol_test)
add_test(
  NAME v2_windows_conversation_participant_protocol
  COMMAND v2_windows_conversation_participant_protocol_test)
add_test(
  NAME v2_windows_conversation_participant_view_model
  COMMAND v2_windows_conversation_participant_view_model_test)
add_test(NAME v2_windows_mention_composer COMMAND v2_windows_mention_composer_test)
add_test(NAME v2_windows_messaging_application COMMAND v2_windows_messaging_application_test)
add_test(NAME v2_windows_session_protocol COMMAND v2_windows_session_protocol_test)
add_test(
  NAME v2_windows_device_management_transport
  COMMAND v2_windows_device_management_transport_test
)
add_test(
  NAME v2_windows_device_management_controller
  COMMAND v2_windows_device_management_controller_test
)
add_test(
  NAME v2_windows_messaging_controller
  COMMAND v2_windows_messaging_controller_test
)
