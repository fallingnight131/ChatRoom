QT += core network sql websockets gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = ChatServer

include(../Common/Common.pri)
include(../Common/Libsodium.pri)

SOURCES += \
    main.cpp \
    ChatServer.cpp \
    ClientSession.cpp \
    InputValidator.cpp \
    DatabaseManager.cpp \
    PasswordHasher.cpp \
    RoomManager.cpp \
    CosManager.cpp

HEADERS += \
    ChatServer.h \
    ClientSession.h \
    InputValidator.h \
    DatabaseManager.h \
    PasswordHasher.h \
    RoomManager.h \
    CosManager.h
