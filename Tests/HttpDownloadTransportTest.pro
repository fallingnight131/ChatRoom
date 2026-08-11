QT += core network
QT -= gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = HttpDownloadTransportTest

SOURCES += \
    HttpDownloadTransportTest.cpp \
    ../Client/HttpDownloadTransport.cpp

HEADERS += \
    ../Client/HttpDownloadTransport.h

INCLUDEPATH += ../Client
