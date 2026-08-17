QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsConversationRuntimeLocalizationTest

INCLUDEPATH += ../Client

SOURCES += \
    WindowsConversationRuntimeLocalizationTest.cpp \
    ../Client/V2WindowsConversationDirectoryViewModel.cpp \
    ../Client/V2WindowsConversationParticipantViewModel.cpp \
    ../Client/V2WindowsMessageSearchViewModel.cpp \
    ../Client/WindowsLocaleCatalog.cpp

HEADERS += \
    ../Client/V2WindowsConversationDirectoryViewModel.h \
    ../Client/V2WindowsConversationParticipantViewModel.h \
    ../Client/V2WindowsMessageSearchViewModel.h \
    ../Client/WindowsLocaleCatalog.h
