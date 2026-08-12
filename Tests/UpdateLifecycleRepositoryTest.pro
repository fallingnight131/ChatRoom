QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateLifecycleRepositoryTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateLifecycleRepositoryTest.cpp \
    ../Client/UpdateLifecycleRepository.cpp \
    ../Client/UpdateLauncherResult.cpp

HEADERS += \
    ../Client/UpdateLifecycleRepository.h \
    ../Client/UpdateLauncherResult.h
