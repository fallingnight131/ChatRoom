QT += core network sql websockets gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = ChatServer

include(../Common/Common.pri)
include(../Common/Libsodium.pri)

SOURCES += \
    main.cpp \
    AuthenticationAbuseGuard.cpp \
    ChatServer.cpp \
    ClientSession.cpp \
    FriendMessageService.cpp \
    InputValidator.cpp \
    RoomMessageService.cpp \
    DatabaseManager.cpp \
    PasswordHasher.cpp \
    RoomManager.cpp \
    CosManager.cpp

HEADERS += \
    AuthenticationAbuseGuard.h \
    ChatServer.h \
    ClientSession.h \
    FriendMessageService.h \
    InputValidator.h \
    RoomMessageService.h \
    DatabaseManager.h \
    PasswordHasher.h \
    RoomManager.h \
    CosManager.h
