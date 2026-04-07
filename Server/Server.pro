QT += core network sql websockets gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = ChatServer

include(../Common/Common.pri)

SOURCES += \
    main.cpp \
    ChatServer.cpp \
    ClientSession.cpp \
    DatabaseManager.cpp \
    RoomManager.cpp \
    CosManager.cpp

HEADERS += \
    ChatServer.h \
    ClientSession.h \
    DatabaseManager.h \
    RoomManager.h \
    CosManager.h
