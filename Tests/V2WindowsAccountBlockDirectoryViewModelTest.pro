QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsAccountBlockDirectoryViewModelTest

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsAccountBlockDirectoryViewModelTest.cpp \
    ../Client/V2WindowsAccountBlockDirectoryViewModel.cpp

HEADERS += ../Client/V2WindowsAccountBlockDirectoryViewModel.h
