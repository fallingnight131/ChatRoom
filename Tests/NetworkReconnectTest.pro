QT += core network
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = NetworkReconnectTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    NetworkReconnectTest.cpp \
    ../Client/NetworkManager.cpp \
    ../Client/HttpUploadTransport.cpp \
    ../Client/HttpDownloadTransport.cpp

HEADERS += \
    ../Client/NetworkManager.h \
    ../Client/HttpUploadTransport.h \
    ../Client/HttpDownloadTransport.h \
    ../Common/Protocol.h
