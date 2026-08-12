QT += core network
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateManifestFetchTransportTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateManifestFetchTransportTest.cpp \
    ../Client/UpdateManifestFetchTransport.cpp

HEADERS += \
    ../Client/UpdateManifestFetchTransport.h
