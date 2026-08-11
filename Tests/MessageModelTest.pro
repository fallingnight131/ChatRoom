QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = MessageModelTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    MessageModelTest.cpp \
    ../Client/MessageModel.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/MessageModel.h \
    ../Common/Message.h
