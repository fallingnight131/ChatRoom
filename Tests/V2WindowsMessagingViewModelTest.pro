QT += core sql
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsMessagingViewModelTest

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsMessagingViewModelTest.cpp \
    ../Client/V2WindowsMessagingViewModel.cpp \
    ../Client/V2LocalMessageRepository.cpp

HEADERS += \
    ../Client/V2WindowsMessagingViewModel.h \
    ../Client/V2LocalMessageRepository.h
