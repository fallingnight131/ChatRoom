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
        chatroom_v1_server_core
        STATIC
        Common/Message.cpp
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
    endif()
endif()
