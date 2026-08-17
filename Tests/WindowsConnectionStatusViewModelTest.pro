QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsConnectionStatusViewModelTest
INCLUDEPATH += ../Client
SOURCES += WindowsConnectionStatusViewModelTest.cpp \
    ../Client/WindowsConnectionStatusViewModel.cpp
HEADERS += ../Client/WindowsConnectionStatusViewModel.h
