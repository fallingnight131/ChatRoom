QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsUpdateStartupServiceTest

INCLUDEPATH += ../Client

SOURCES += \
    WindowsUpdateStartupServiceTest.cpp \
    ../Client/WindowsUpdateStartupService.cpp \
    ../Client/WindowsUpdateRuntimePaths.cpp \
    ../Client/UpdateLifecycleRepository.cpp \
    ../Client/UpdateLauncherResult.cpp

HEADERS += \
    ../Client/WindowsUpdateStartupService.h \
    ../Client/WindowsUpdateRuntimePaths.h \
    ../Client/UpdateLifecycleRepository.h \
    ../Client/UpdateLauncherResult.h
