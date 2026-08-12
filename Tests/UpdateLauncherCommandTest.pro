QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateLauncherCommandTest

INCLUDEPATH += ../UpdaterLauncher

SOURCES += \
    UpdateLauncherCommandTest.cpp \
    ../UpdaterLauncher/UpdateLauncherCommand.cpp

HEADERS += ../UpdaterLauncher/UpdateLauncherCommand.h
