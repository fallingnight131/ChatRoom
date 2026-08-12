QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsClientInstanceGuardTest

INCLUDEPATH += ../Client

SOURCES += \
    WindowsClientInstanceGuardTest.cpp \
    ../Client/WindowsClientInstanceGuard.cpp

HEADERS += \
    ../Client/WindowsClientInstanceGuard.h
