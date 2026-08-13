QT += core
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = DeviceManagementViewModelTest
INCLUDEPATH += ../Client
SOURCES += DeviceManagementViewModelTest.cpp \
    ../Client/DeviceManagementViewModel.cpp
HEADERS += ../Client/DeviceManagementViewModel.h
