QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateLauncherResultTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateLauncherResultTest.cpp \
    ../Client/UpdateLauncherResult.cpp

HEADERS += ../Client/UpdateLauncherResult.h
