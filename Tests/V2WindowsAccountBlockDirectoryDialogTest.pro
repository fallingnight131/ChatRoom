QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsAccountBlockDirectoryDialogTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsAccountBlockDirectoryDialogTest.cpp \
    ../Client/V2WindowsAccountBlockDirectoryDialog.cpp \
    ../Client/V2WindowsAccountBlockDirectoryViewModel.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp

HEADERS += \
    ../Client/V2WindowsAccountBlockDirectoryDialog.h \
    ../Client/V2WindowsAccountBlockDirectoryViewModel.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h
