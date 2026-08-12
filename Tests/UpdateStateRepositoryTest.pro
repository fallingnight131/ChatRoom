QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateStateRepositoryTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateStateRepositoryTest.cpp \
    ../Client/UpdateStateRepository.cpp

HEADERS += \
    ../Client/UpdateStateRepository.h
