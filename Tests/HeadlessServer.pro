QT += core network sql websockets
QT -= gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = ChatServerHeadless

DEFINES += \
    CHATROOM_DISABLE_IMAGE_THUMBNAILS \
    CHATROOM_ENABLE_BENCHMARK_METRICS

INCLUDEPATH += \
    ../Common \
    ../Server

SOURCES += \
    ../Common/Message.cpp \
    ../Server/main.cpp \
    ../Server/ChatServer.cpp \
    ../Server/ClientSession.cpp \
    ../Server/DatabaseManager.cpp \
    ../Server/RoomManager.cpp \
    ../Server/CosManager.cpp

HEADERS += \
    ../Common/Message.h \
    ../Common/Protocol.h \
    ../Server/ChatServer.h \
    ../Server/ClientSession.h \
    ../Server/DatabaseManager.h \
    ../Server/RoomManager.h \
    ../Server/CosManager.h
