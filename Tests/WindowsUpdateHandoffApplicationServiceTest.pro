QT += core concurrent
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsUpdateHandoffApplicationServiceTest

INCLUDEPATH += ../Client ../UpdaterLauncher

SOURCES += \
    WindowsUpdateHandoffApplicationServiceTest.cpp \
    ../Client/WindowsUpdateHandoffApplicationService.cpp \
    ../UpdaterLauncher/UpdateLauncherCommand.cpp

HEADERS += \
    ../Client/WindowsUpdateHandoffApplicationService.h \
    ../UpdaterLauncher/UpdateLauncherCommand.h
