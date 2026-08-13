QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2LocalMessageRepositoryTest

INCLUDEPATH += ../Client

SOURCES += \
    V2LocalMessageRepositoryTest.cpp \
    ../Client/V2LocalMessageRepository.cpp

HEADERS += ../Client/V2LocalMessageRepository.h
