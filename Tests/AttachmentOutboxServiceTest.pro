QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = AttachmentOutboxServiceTest

INCLUDEPATH += ../Client ../Common

SOURCES += \
    AttachmentOutboxServiceTest.cpp \
    ../Client/AttachmentOutboxService.cpp \
    ../Client/LocalConversationRepository.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/AttachmentOutboxService.h \
    ../Client/LocalConversationRepository.h \
    ../Common/Message.h
