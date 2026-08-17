QT += core gui widgets
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsAvatarCropLocalizationTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    WindowsAvatarCropLocalizationTest.cpp \
    ../Client/AvatarCropDialog.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp

HEADERS += \
    ../Client/AvatarCropDialog.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h
