cmake_minimum_required(VERSION 3.24)

project(ChatRoom LANGUAGES CXX)

option(CHATROOM_BUILD_HEADLESS_SERVER "Build the Qt V1 headless server" ON)
include(CTest)

if(CHATROOM_BUILD_HEADLESS_SERVER)
    find_package(Qt6 6.5 REQUIRED COMPONENTS Core Network Sql WebSockets)

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
    endif()
endif()
