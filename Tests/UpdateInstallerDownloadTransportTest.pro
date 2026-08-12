QT += core network
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateInstallerDownloadTransportTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateInstallerDownloadTransportTest.cpp \
    ../Client/UpdateInstallerDownloadTransport.cpp

HEADERS += \
    ../Client/UpdateInstallerDownloadTransport.h
