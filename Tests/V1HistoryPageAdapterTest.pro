QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V1HistoryPageAdapterTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    V1HistoryPageAdapterTest.cpp \
    ../Client/V1HistoryPageAdapter.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/V1HistoryPageAdapter.h \
    ../Common/Protocol.h \
    ../Common/Message.h
