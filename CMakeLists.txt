cmake_minimum_required(VERSION 3.24)

project(ChatRoom LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(CHATROOM_BUILD_HEADLESS_SERVER "Build the Qt V1 headless server" ON)
option(CHATROOM_BUILD_WINDOWS_CLIENT "Build the supported Windows Qt client" OFF)
option(CHATROOM_ENABLE_WINDOWS_UPDATES
       "Compile reviewed public Windows update trust configuration" OFF)
set(CHATROOM_UPDATE_CHANNEL "" CACHE STRING "Windows update channel")
set(CHATROOM_UPDATE_MANIFEST_URL "" CACHE STRING "Windows update manifest HTTPS URL")
set(CHATROOM_UPDATE_PRIMARY_KEY_ID "" CACHE STRING "Primary Windows update public-key ID")
set(CHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX "" CACHE STRING "Primary Ed25519 public key")
set(CHATROOM_UPDATE_SECONDARY_KEY_ID "" CACHE STRING "Secondary Windows update public-key ID")
set(CHATROOM_UPDATE_SECONDARY_PUBLIC_KEY_HEX "" CACHE STRING "Secondary Ed25519 public key")
include(cmake/WindowsUpdateConfiguration.cmake)
chatroom_validate_windows_update_configuration()
include(CTest)

if(CHATROOM_BUILD_WINDOWS_CLIENT AND NOT WIN32)
    message(FATAL_ERROR "CHATROOM_BUILD_WINDOWS_CLIENT requires a Windows host")
endif()
if(CHATROOM_BUILD_WINDOWS_CLIENT AND NOT CHATROOM_BUILD_HEADLESS_SERVER)
    message(FATAL_ERROR
        "CHATROOM_BUILD_WINDOWS_CLIENT currently requires the shared Qt V1 graph")
endif()
if(CHATROOM_ENABLE_WINDOWS_UPDATES AND NOT CHATROOM_BUILD_WINDOWS_CLIENT)
    message(FATAL_ERROR
        "CHATROOM_ENABLE_WINDOWS_UPDATES requires CHATROOM_BUILD_WINDOWS_CLIENT=ON")
endif()

if(CHATROOM_BUILD_HEADLESS_SERVER)
    set(CHATROOM_QT_COMPONENTS Core Concurrent Network Sql WebSockets)
    if(CHATROOM_BUILD_WINDOWS_CLIENT)
        list(APPEND CHATROOM_QT_COMPONENTS Gui Widgets Multimedia)
    endif()
    find_package(Qt6 6.5 REQUIRED COMPONENTS ${CHATROOM_QT_COMPONENTS})

    set(SODIUM_ROOT "" CACHE PATH "libsodium installation prefix")
    find_path(
        SODIUM_INCLUDE_DIR
        NAMES sodium.h
        HINTS "${SODIUM_ROOT}" ENV SODIUM_ROOT
        PATH_SUFFIXES include
        REQUIRED
    )
    find_library(
        SODIUM_LIBRARY
        NAMES sodium libsodium
        HINTS "${SODIUM_ROOT}" ENV SODIUM_ROOT
        PATH_SUFFIXES lib
        REQUIRED
    )
    add_library(Sodium::Sodium UNKNOWN IMPORTED)
    set_target_properties(
        Sodium::Sodium
        PROPERTIES
            IMPORTED_LOCATION "${SODIUM_LIBRARY}"
            INTERFACE_INCLUDE_DIRECTORIES "${SODIUM_INCLUDE_DIR}"
    )

    add_library(
        chatroom_v1_persistence
        STATIC
        Server/DatabaseManager.cpp
        Server/PasswordHasher.cpp
        Server/DatabaseManager.h
        Server/PasswordHasher.h
    )
    set_target_properties(
        chatroom_v1_persistence
        PROPERTIES
            AUTOMOC ON
            CXX_STANDARD 17
            CXX_STANDARD_REQUIRED ON
            CXX_EXTENSIONS OFF
    )
    target_include_directories(chatroom_v1_persistence PUBLIC Server)
    target_link_libraries(
        chatroom_v1_persistence
        PUBLIC
            Qt6::Core
            Qt6::Sql
            Sodium::Sodium
    )

    add_library(
        chatroom_v1_common
        STATIC
        Common/Message.cpp
        Common/Message.h
        Common/Protocol.h
    )
    set_target_properties(
        chatroom_v1_common
        PROPERTIES
            CXX_STANDARD 17
            CXX_STANDARD_REQUIRED ON
            CXX_EXTENSIONS OFF
    )
    target_include_directories(chatroom_v1_common PUBLIC Common)
    target_link_libraries(chatroom_v1_common PUBLIC Qt6::Core)

    add_library(
        chatroom_v1_server_core
        STATIC
        Server/AuthenticationAbuseGuard.cpp
        Server/ChatServer.cpp
        Server/ClientSession.cpp
        Server/FriendMessageService.cpp
        Server/InputValidator.cpp
        Server/RoomMessageService.cpp
        Server/AdministrativeDeletionService.cpp
        Server/RoomManager.cpp
        Server/CosManager.cpp
        Common/Message.h
        Common/Protocol.h
        Server/AuthenticationAbuseGuard.h
        Server/ChatServer.h
        Server/ClientSession.h
        Server/FriendMessageService.h
        Server/InputValidator.h
        Server/RoomMessageService.h
        Server/AdministrativeDeletionService.h
        Server/RoomManager.h
        Server/CosManager.h
    )
    set_target_properties(
        chatroom_v1_server_core
        PROPERTIES
            AUTOMOC ON
            CXX_STANDARD 17
            CXX_STANDARD_REQUIRED ON
            CXX_EXTENSIONS OFF
    )
    target_compile_definitions(
        chatroom_v1_server_core
        PRIVATE
            CHATROOM_DISABLE_IMAGE_THUMBNAILS
            CHATROOM_ENABLE_BENCHMARK_METRICS
    )
    target_include_directories(chatroom_v1_server_core PUBLIC Common Server)
    target_link_libraries(
        chatroom_v1_server_core
        PUBLIC
            Qt6::Core
            Qt6::Network
            Qt6::Sql
            Qt6::WebSockets
            Sodium::Sodium
            chatroom_v1_persistence
            chatroom_v1_common
    )

    add_executable(ChatServerHeadless Server/main.cpp)
    set_target_properties(
        ChatServerHeadless
        PROPERTIES
            CXX_STANDARD 17
            CXX_STANDARD_REQUIRED ON
            CXX_EXTENSIONS OFF
    )
    target_link_libraries(ChatServerHeadless PRIVATE chatroom_v1_server_core)

    if(BUILD_TESTING)
        find_package(Python3 REQUIRED COMPONENTS Interpreter)
    endif()

    if(BUILD_TESTING OR CHATROOM_BUILD_WINDOWS_CLIENT)
        add_library(
            chatroom_client_local_data
            STATIC
            Client/MessageModel.cpp
            Client/LocalConversationRepository.cpp
            Client/OutgoingMessageService.cpp
            Client/ConversationSyncService.cpp
            Client/AttachmentOutboxService.cpp
            Client/V1HistoryPageAdapter.cpp
            Client/MessageModel.h
            Client/LocalConversationRepository.h
            Client/OutgoingMessageService.h
            Client/ConversationSyncService.h
            Client/AttachmentOutboxService.h
            Client/V1HistoryPageAdapter.h
        )

        add_library(
            chatroom_device_management_ui_core
            STATIC
            Client/DeviceManagementViewModel.cpp
            Client/DeviceManagementViewModel.h
        )
        set_target_properties(
            chatroom_device_management_ui_core
            PROPERTIES AUTOMOC ON CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
        target_include_directories(chatroom_device_management_ui_core PUBLIC Client)
        target_link_libraries(chatroom_device_management_ui_core PUBLIC Qt6::Core)
        set_target_properties(
            chatroom_client_local_data
            PROPERTIES
                AUTOMOC ON
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_include_directories(chatroom_client_local_data PUBLIC Client)
        target_link_libraries(
            chatroom_client_local_data
            PUBLIC Qt6::Core Qt6::Sql chatroom_v1_common
        )

        add_library(
            chatroom_client_transport
            STATIC
            Client/HttpUploadTransport.cpp
            Client/HttpDownloadTransport.cpp
            Client/NetworkManager.cpp
            Client/HttpUploadTransport.h
            Client/HttpDownloadTransport.h
            Client/NetworkManager.h
        )
        set_target_properties(
            chatroom_client_transport
            PROPERTIES
                AUTOMOC ON
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_include_directories(chatroom_client_transport PUBLIC Client)
        target_link_libraries(
            chatroom_client_transport
            PUBLIC Qt6::Core Qt6::Network chatroom_v1_common
        )

        add_library(
            chatroom_windows_update_trust_core
            STATIC
            Client/UpdateManifestSignatureVerifier.cpp
            Client/UpdateManifestDecisionPolicy.cpp
            Client/UpdateStateRepository.cpp
            Client/UpdateManifestApplicationService.cpp
            Client/UpdateManifestSignatureVerifier.h
            Client/UpdateManifestDecisionPolicy.h
            Client/UpdateStateRepository.h
            Client/UpdateManifestApplicationService.h
        )
        set_target_properties(
            chatroom_windows_update_trust_core
            PROPERTIES
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_include_directories(chatroom_windows_update_trust_core PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_trust_core
            PUBLIC Qt6::Core Sodium::Sodium
        )

        add_library(
            chatroom_windows_update_transport
            STATIC
            Client/UpdateManifestFetchTransport.cpp
            Client/UpdateInstallerDownloadTransport.cpp
            Client/UpdateManifestFetchTransport.h
            Client/UpdateInstallerDownloadTransport.h
        )
        set_target_properties(
            chatroom_windows_update_transport
            PROPERTIES
                AUTOMOC ON
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_include_directories(chatroom_windows_update_transport PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_transport
            PUBLIC Qt6::Core Qt6::Network
        )

        add_library(
            chatroom_windows_installer_trust
            STATIC
            Client/UpdateInstallerTrustVerifier.cpp
            Client/UpdateInstallerTrustVerifier.h
        )
        set_target_properties(
            chatroom_windows_installer_trust
            PROPERTIES
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_include_directories(chatroom_windows_installer_trust PUBLIC Client)
        target_link_libraries(chatroom_windows_installer_trust PUBLIC Qt6::Core)
        if(WIN32)
            target_link_libraries(
                chatroom_windows_installer_trust PUBLIC wintrust crypt32)
        endif()

        add_library(
            chatroom_windows_update_orchestration
            STATIC
            Client/UpdatePreparationApplicationService.cpp
            Client/UpdateCheckApplicationService.cpp
            Client/UpdatePreparationApplicationService.h
            Client/UpdateCheckApplicationService.h
        )
        set_target_properties(
            chatroom_windows_update_orchestration
            PROPERTIES AUTOMOC ON
        )
        target_include_directories(
            chatroom_windows_update_orchestration PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_orchestration
            PUBLIC
                Qt6::Core
                Qt6::Network
                Qt6::Concurrent
                chatroom_windows_update_trust_core
                chatroom_windows_update_transport
                chatroom_windows_installer_trust
        )

        add_library(
            chatroom_windows_update_lifecycle
            STATIC
            Client/UpdateLauncherResult.cpp
            Client/UpdateLifecycleRepository.cpp
            Client/WindowsUpdateRuntimePaths.cpp
            Client/WindowsUpdateStartupService.cpp
            Client/UpdateLauncherResult.h
            Client/UpdateLifecycleRepository.h
            Client/WindowsUpdateRuntimePaths.h
            Client/WindowsUpdateStartupService.h
        )
        target_include_directories(
            chatroom_windows_update_lifecycle PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_lifecycle PUBLIC Qt6::Core)

        add_library(
            chatroom_update_launcher_protocol
            STATIC
            UpdaterLauncher/UpdateLauncherCommand.cpp
            UpdaterLauncher/UpdateLauncherCommand.h
        )
        target_include_directories(
            chatroom_update_launcher_protocol PUBLIC UpdaterLauncher)
        target_link_libraries(
            chatroom_update_launcher_protocol PUBLIC Qt6::Core)

        add_library(
            chatroom_windows_update_handoff
            STATIC
            Client/WindowsUpdateHandoffApplicationService.cpp
            Client/WindowsUpdateInstallCoordinator.cpp
            Client/WindowsUpdateHandoffApplicationService.h
            Client/WindowsUpdateInstallCoordinator.h
        )
        set_target_properties(
            chatroom_windows_update_handoff
            PROPERTIES AUTOMOC ON
        )
        target_include_directories(
            chatroom_windows_update_handoff PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_handoff
            PUBLIC
                Qt6::Core
                Qt6::Concurrent
                chatroom_windows_update_orchestration
                chatroom_windows_update_lifecycle
        )

        add_library(
            chatroom_windows_update_product_configuration
            STATIC
            Client/WindowsUpdateProductConfiguration.cpp
            Client/WindowsUpdateProductConfiguration.h
            Client/WindowsUpdateTrustDiagnostic.cpp
            Client/WindowsUpdateTrustDiagnostic.h
        )
        target_include_directories(
            chatroom_windows_update_product_configuration PUBLIC Client)
        target_link_libraries(
            chatroom_windows_update_product_configuration
            PUBLIC Qt6::Core chatroom_windows_update_trust_core)

        if(CHATROOM_BUILD_WINDOWS_CLIENT)
            enable_language(RC)
            file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/VERSION"
                 CHATROOM_PRODUCT_VERSION LIMIT_COUNT 1)
            if(NOT CHATROOM_PRODUCT_VERSION MATCHES
                    "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
                message(FATAL_ERROR "VERSION must contain one numeric semantic version")
            endif()
            string(REPLACE "." "," CHATROOM_PRODUCT_VERSION_COMMAS
                   "${CHATROOM_PRODUCT_VERSION}")
            set(CHATROOM_RC_ICON
                "${CMAKE_CURRENT_SOURCE_DIR}/Client/resources/app_icon.ico")
            set(CHATROOM_RC_FILE_DESCRIPTION "Chat Room Windows Client")
            set(CHATROOM_RC_ORIGINAL_FILENAME "ChatClient.exe")
            configure_file(
                Client/resources/windows_product.rc.in
                generated/ChatClient.rc @ONLY)
            set(CHATROOM_RC_FILE_DESCRIPTION "Chat Room Update Launcher")
            set(CHATROOM_RC_ORIGINAL_FILENAME "ChatRoomUpdateLauncher.exe")
            configure_file(
                Client/resources/windows_product.rc.in
                generated/ChatRoomUpdateLauncher.rc @ONLY)

            add_executable(
                ChatClient WIN32
                Client/main.cpp
                Client/LoginDialog.cpp
                Client/ChatWindow.cpp
                Client/MessageDelegate.cpp
                Client/EmojiPicker.cpp
                Client/ThemeManager.cpp
                Client/TrayManager.cpp
                Client/FileCache.cpp
                Client/WindowsClientInstanceGuard.cpp
                Client/WindowsUpdateProductConfiguration.cpp
                Client/WindowsUpdateTrustDiagnostic.cpp
                Client/WindowsUpdateController.cpp
                Client/AvatarCropDialog.cpp
                Client/ForwardSelectDialog.cpp
                Client/RoomSettingsDialog.cpp
                Client/RoomFileManagerDialog.cpp
                Client/ProfileDialog.cpp
                Client/UserInfoDialog.cpp
                Client/DeviceManagementDialog.cpp
                Client/DeviceManagementDialog.h
                Client/resources/resources.qrc
                "${CMAKE_CURRENT_BINARY_DIR}/generated/ChatClient.rc"
            )
            set_target_properties(
                ChatClient
                PROPERTIES
                    AUTOMOC ON
                    AUTORCC ON
                    VERSION "${CHATROOM_PRODUCT_VERSION}"
            )
            target_compile_definitions(
                ChatClient PRIVATE CHAT_APP_VERSION="${CHATROOM_PRODUCT_VERSION}")
            if(CHATROOM_ENABLE_WINDOWS_UPDATES)
                target_compile_definitions(
                    ChatClient
                    PRIVATE
                        CHAT_UPDATE_CONFIGURATION_ENABLED=1
                        CHAT_UPDATE_CHANNEL="${CHATROOM_UPDATE_CHANNEL}"
                        CHAT_UPDATE_MANIFEST_URL="${CHATROOM_UPDATE_MANIFEST_URL}"
                        CHAT_UPDATE_PRIMARY_KEY_ID="${CHATROOM_UPDATE_PRIMARY_KEY_ID}"
                        CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX="${CHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX}"
                )
                if(NOT CHATROOM_UPDATE_SECONDARY_KEY_ID STREQUAL "")
                    target_compile_definitions(
                        ChatClient
                        PRIVATE
                            CHAT_UPDATE_SECONDARY_KEY_ID="${CHATROOM_UPDATE_SECONDARY_KEY_ID}"
                            CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX="${CHATROOM_UPDATE_SECONDARY_PUBLIC_KEY_HEX}"
                    )
                endif()
            endif()
            target_include_directories(ChatClient PRIVATE Client Common)
            target_link_libraries(
                ChatClient
                PRIVATE
                    Qt6::Core
                    Qt6::Gui
                    Qt6::Widgets
                    Qt6::Network
                    Qt6::Multimedia
                    Qt6::Sql
                    chatroom_client_local_data
                    chatroom_client_transport
                    chatroom_device_management_ui_core
                    chatroom_windows_update_orchestration
                    chatroom_windows_update_lifecycle
                    chatroom_windows_update_handoff
                    ole32
                    uuid
                    gdi32
            )

            add_executable(
                ChatRoomUpdateLauncher WIN32
                UpdaterLauncher/main.cpp
                "${CMAKE_CURRENT_BINARY_DIR}/generated/ChatRoomUpdateLauncher.rc"
            )
            target_include_directories(
                ChatRoomUpdateLauncher PRIVATE Client UpdaterLauncher)
            target_link_libraries(
                ChatRoomUpdateLauncher
                PRIVATE
                    Qt6::Core
                    chatroom_update_launcher_protocol
                    chatroom_windows_installer_trust
            )
            set_target_properties(
                ChatRoomUpdateLauncher
                PROPERTIES VERSION "${CHATROOM_PRODUCT_VERSION}")
        endif()

        if(BUILD_TESTING)

        function(chatroom_add_local_data_test target test_name)
            add_executable(${target} Tests/${target}.cpp)
            set_target_properties(
                ${target}
                PROPERTIES
                    CXX_STANDARD 17
                    CXX_STANDARD_REQUIRED ON
                    CXX_EXTENSIONS OFF
            )
            target_link_libraries(${target} PRIVATE chatroom_client_local_data)
            add_test(NAME ${test_name} COMMAND ${target})
        endfunction()

        function(chatroom_add_transport_test target test_name)
            add_executable(${target} Tests/${target}.cpp)
            set_target_properties(
                ${target}
                PROPERTIES
                    CXX_STANDARD 17
                    CXX_STANDARD_REQUIRED ON
                    CXX_EXTENSIONS OFF
            )
            target_link_libraries(${target} PRIVATE chatroom_client_transport)
            add_test(NAME ${test_name} COMMAND ${target})
            set_tests_properties(${test_name} PROPERTIES TIMEOUT 30)
        endfunction()

        function(chatroom_add_update_trust_test target test_name)
            add_executable(${target} Tests/${target}.cpp)
            set_target_properties(
                ${target}
                PROPERTIES
                    CXX_STANDARD 17
                    CXX_STANDARD_REQUIRED ON
                    CXX_EXTENSIONS OFF
            )
            target_link_libraries(${target} PRIVATE chatroom_windows_update_trust_core)
            add_test(NAME ${test_name} COMMAND ${target})
            set_tests_properties(${test_name} PROPERTIES TIMEOUT 30)
        endfunction()

        add_executable(DatabaseSchemaTest Tests/DatabaseSchemaTest.cpp)
        set_target_properties(
            DatabaseSchemaTest
            PROPERTIES
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_link_libraries(DatabaseSchemaTest PRIVATE chatroom_v1_persistence)
        add_test(NAME v1_database_schema COMMAND DatabaseSchemaTest)

        add_executable(PasswordMigrationTest Tests/PasswordMigrationTest.cpp)
        set_target_properties(
            PasswordMigrationTest
            PROPERTIES
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_link_libraries(PasswordMigrationTest PRIVATE chatroom_v1_persistence)
        add_test(NAME v1_password_migration COMMAND PasswordMigrationTest)

        chatroom_add_local_data_test(MessageModelTest v1_client_message_model)
        chatroom_add_local_data_test(LocalConversationRepositoryTest v1_client_local_repository)
        chatroom_add_local_data_test(OutgoingMessageServiceTest v1_client_outgoing_message)
        chatroom_add_local_data_test(ConversationSyncServiceTest v1_client_conversation_sync)
        chatroom_add_local_data_test(AttachmentOutboxServiceTest v1_client_attachment_outbox)
        chatroom_add_local_data_test(V1HistoryPageAdapterTest v1_client_history_adapter)

        add_executable(DeviceManagementViewModelTest Tests/DeviceManagementViewModelTest.cpp)
        target_link_libraries(DeviceManagementViewModelTest PRIVATE chatroom_device_management_ui_core)
        add_test(NAME m6_windows_device_management_view_model COMMAND DeviceManagementViewModelTest)

        chatroom_add_transport_test(HttpUploadTransportTest v1_client_http_upload)
        chatroom_add_transport_test(HttpDownloadTransportTest v1_client_http_download)
        chatroom_add_transport_test(NetworkReconnectTest v1_client_network_reconnect)

        add_executable(NetworkTlsPolicyTest Tests/NetworkTlsPolicyTest.cpp)
        set_target_properties(
            NetworkTlsPolicyTest
            PROPERTIES
                CXX_STANDARD 17
                CXX_STANDARD_REQUIRED ON
                CXX_EXTENSIONS OFF
        )
        target_link_libraries(NetworkTlsPolicyTest PRIVATE chatroom_client_transport)
        add_test(
            NAME v1_client_tls_trust_policy
            COMMAND
                "${Python3_EXECUTABLE}"
                "${CMAKE_CURRENT_SOURCE_DIR}/Tests/network_tls_policy_test.py"
                --client "$<TARGET_FILE:NetworkTlsPolicyTest>"
        )
        set_tests_properties(v1_client_tls_trust_policy PROPERTIES TIMEOUT 30)

        chatroom_add_update_trust_test(
            UpdateManifestSignatureVerifierTest m4_update_signature_verifier)
        chatroom_add_update_trust_test(
            UpdateManifestDecisionPolicyTest m4_update_decision_policy)
        chatroom_add_update_trust_test(
            UpdateStateRepositoryTest m4_update_state_repository)
        chatroom_add_update_trust_test(
            UpdateManifestApplicationServiceTest m4_update_manifest_application)

        add_executable(
            UpdateManifestFetchTransportTest
            Tests/UpdateManifestFetchTransportTest.cpp)
        target_link_libraries(
            UpdateManifestFetchTransportTest PRIVATE chatroom_windows_update_transport)
        add_test(
            NAME m4_update_manifest_fetch_transport
            COMMAND UpdateManifestFetchTransportTest)

        add_executable(
            UpdateInstallerDownloadTransportTest
            Tests/UpdateInstallerDownloadTransportTest.cpp)
        target_link_libraries(
            UpdateInstallerDownloadTransportTest PRIVATE chatroom_windows_update_transport)
        add_test(
            NAME m4_update_installer_download_transport
            COMMAND UpdateInstallerDownloadTransportTest)

        add_executable(
            UpdateInstallerTrustVerifierTest
            Tests/UpdateInstallerTrustVerifierTest.cpp)
        target_link_libraries(
            UpdateInstallerTrustVerifierTest PRIVATE chatroom_windows_installer_trust)
        add_test(
            NAME m4_update_installer_trust_verifier
            COMMAND UpdateInstallerTrustVerifierTest)
        set_tests_properties(
            m4_update_manifest_fetch_transport
            m4_update_installer_download_transport
            m4_update_installer_trust_verifier
            PROPERTIES TIMEOUT 30)

        add_executable(
            UpdatePreparationApplicationServiceTest
            Tests/UpdatePreparationApplicationServiceTest.cpp)
        target_link_libraries(
            UpdatePreparationApplicationServiceTest
            PRIVATE chatroom_windows_update_orchestration)
        add_test(
            NAME m4_update_preparation_application
            COMMAND UpdatePreparationApplicationServiceTest)

        add_executable(
            UpdateCheckApplicationServiceTest
            Tests/UpdateCheckApplicationServiceTest.cpp)
        target_link_libraries(
            UpdateCheckApplicationServiceTest
            PRIVATE chatroom_windows_update_orchestration)
        add_test(
            NAME m4_update_complete_check_application
            COMMAND UpdateCheckApplicationServiceTest)
        set_tests_properties(
            m4_update_preparation_application
            m4_update_complete_check_application
            PROPERTIES TIMEOUT 30)

        function(chatroom_add_update_test target test_name library)
            add_executable(${target} Tests/${target}.cpp)
            target_link_libraries(${target} PRIVATE ${library})
            add_test(NAME ${test_name} COMMAND ${target})
            set_tests_properties(${test_name} PROPERTIES TIMEOUT 30)
        endfunction()

        chatroom_add_update_test(
            UpdateLauncherResultTest
            m4_update_launcher_result
            chatroom_windows_update_lifecycle)
        chatroom_add_update_test(
            UpdateLifecycleRepositoryTest
            m4_update_lifecycle_repository
            chatroom_windows_update_lifecycle)
        chatroom_add_update_test(
            WindowsUpdateStartupServiceTest
            m4_update_startup_reconciliation
            chatroom_windows_update_lifecycle)
        chatroom_add_update_test(
            UpdateLauncherCommandTest
            m4_update_launcher_command
            chatroom_update_launcher_protocol)

        add_executable(
            WindowsUpdateHandoffApplicationServiceTest
            Tests/WindowsUpdateHandoffApplicationServiceTest.cpp)
        target_link_libraries(
            WindowsUpdateHandoffApplicationServiceTest
            PRIVATE
                chatroom_windows_update_handoff
                chatroom_update_launcher_protocol)
        add_test(
            NAME m4_update_handoff_application
            COMMAND WindowsUpdateHandoffApplicationServiceTest)

        chatroom_add_update_test(
            WindowsUpdateInstallCoordinatorTest
            m4_update_install_coordinator
            chatroom_windows_update_handoff)

        chatroom_add_update_test(
            WindowsUpdateProductConfigurationTest
            m4_update_product_configuration_disabled
            chatroom_windows_update_product_configuration)
        add_executable(
            WindowsUpdateProductConfigurationEnabledTest
            Tests/WindowsUpdateProductConfigurationEnabledTest.cpp
            Client/WindowsUpdateProductConfiguration.cpp
            Client/WindowsUpdateTrustDiagnostic.cpp)
        target_include_directories(
            WindowsUpdateProductConfigurationEnabledTest PRIVATE Client)
        target_compile_definitions(
            WindowsUpdateProductConfigurationEnabledTest
            PRIVATE
                CHAT_UPDATE_CONFIGURATION_ENABLED=1
                CHAT_UPDATE_CHANNEL="stable"
                CHAT_UPDATE_MANIFEST_URL="https://updates.example.test/windows/stable/manifest.json"
                CHAT_UPDATE_PRIMARY_KEY_ID="windows-update-2026-01"
                CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
        target_link_libraries(
            WindowsUpdateProductConfigurationEnabledTest
            PRIVATE Qt6::Core chatroom_windows_update_trust_core)
        add_test(
            NAME m4_update_product_configuration_enabled
            COMMAND WindowsUpdateProductConfigurationEnabledTest)
        set_tests_properties(
            m4_update_handoff_application
            m4_update_install_coordinator
            m4_update_product_configuration_disabled
            m4_update_product_configuration_enabled
            PROPERTIES TIMEOUT 30)
        endif()
    endif()
endif()
