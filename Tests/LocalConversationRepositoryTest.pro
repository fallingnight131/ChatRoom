QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = LocalConversationRepositoryTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    LocalConversationRepositoryTest.cpp \
    ../Client/LocalConversationRepository.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/LocalConversationRepository.h \
    ../Common/Message.h
