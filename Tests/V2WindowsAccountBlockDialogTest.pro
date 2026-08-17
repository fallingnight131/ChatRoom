QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsAccountBlockDialogTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsAccountBlockDialogTest.cpp \
    ../Client/V2WindowsAccountBlockDialog.cpp \
    ../Client/V2WindowsAccountBlockViewModel.cpp \
    ../Client/V2WindowsConversationParticipantViewModel.cpp \
    ../Client/WindowsLocaleCatalog.cpp

HEADERS += \
    ../Client/V2WindowsAccountBlockDialog.h \
    ../Client/V2WindowsAccountBlockViewModel.h \
    ../Client/V2WindowsConversationParticipantViewModel.h \
    ../Client/WindowsLocaleCatalog.h
