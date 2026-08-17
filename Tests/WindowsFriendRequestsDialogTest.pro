QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsFriendRequestsDialogTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

SOURCES += \
    WindowsFriendRequestsDialogTest.cpp \
    ../Client/FriendRequestsDialog.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp

HEADERS += \
    ../Client/FriendRequestsDialog.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h

INCLUDEPATH += ../Client
