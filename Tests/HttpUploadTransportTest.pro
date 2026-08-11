QT += core network
QT -= gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = HttpUploadTransportTest

INCLUDEPATH += ../Client

SOURCES += \
    HttpUploadTransportTest.cpp \
    ../Client/HttpUploadTransport.cpp

HEADERS += \
    ../Client/HttpUploadTransport.h
