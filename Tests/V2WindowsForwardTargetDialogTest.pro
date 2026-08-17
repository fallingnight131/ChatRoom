QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsForwardTargetDialogTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsForwardTargetDialogTest.cpp \
    ../Client/V2WindowsForwardTargetDialog.cpp \
    ../Client/WindowsLocaleCatalog.cpp

HEADERS += \
    ../Client/V2WindowsForwardTargetDialog.h \
    ../Client/V2WindowsConversationDirectoryViewModel.h \
    ../Client/WindowsLocaleCatalog.h
