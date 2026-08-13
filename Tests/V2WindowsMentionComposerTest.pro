QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsMentionComposerTest
INCLUDEPATH += ../Client
SOURCES += V2WindowsMentionComposerTest.cpp \
           ../Client/V2WindowsMentionComposer.cpp
HEADERS += ../Client/V2WindowsMentionComposer.h \
           ../Client/V2LocalMessageRepository.h
