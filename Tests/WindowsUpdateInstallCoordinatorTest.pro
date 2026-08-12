QT += core concurrent
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsUpdateInstallCoordinatorTest

INCLUDEPATH += ../Client

SOURCES += \
    WindowsUpdateInstallCoordinatorTest.cpp \
    ../Client/WindowsUpdateInstallCoordinator.cpp \
    ../Client/WindowsUpdateHandoffApplicationService.cpp \
    ../Client/UpdateLifecycleRepository.cpp \
    ../Client/UpdateLauncherResult.cpp

HEADERS += \
    ../Client/WindowsUpdateInstallCoordinator.h \
    ../Client/WindowsUpdateHandoffApplicationService.h \
    ../Client/UpdateLifecycleRepository.h \
    ../Client/UpdateLauncherResult.h
