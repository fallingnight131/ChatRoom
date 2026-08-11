QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = ConversationSyncServiceTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    ConversationSyncServiceTest.cpp \
    ../Client/ConversationSyncService.cpp \
    ../Client/LocalConversationRepository.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/ConversationSyncService.h \
    ../Client/LocalConversationRepository.h \
    ../Common/Message.h
