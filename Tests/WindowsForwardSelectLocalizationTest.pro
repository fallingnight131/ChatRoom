QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsForwardSelectLocalizationTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    WindowsForwardSelectLocalizationTest.cpp \
    ../Client/ForwardSelectDialog.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp

HEADERS += \
    ../Client/ForwardSelectDialog.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h
