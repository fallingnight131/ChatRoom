QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = OutgoingMessageServiceTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    OutgoingMessageServiceTest.cpp \
    ../Client/OutgoingMessageService.cpp \
    ../Client/LocalConversationRepository.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/OutgoingMessageService.h \
    ../Client/LocalConversationRepository.h \
    ../Common/Message.h
