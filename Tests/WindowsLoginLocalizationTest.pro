QT += core gui widgets network concurrent
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsLoginLocalizationTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client ../Common

SOURCES += \
    WindowsLoginLocalizationTest.cpp \
    ../Client/LoginDialog.cpp \
    ../Client/NetworkManager.cpp \
    ../Client/HttpUploadTransport.cpp \
    ../Client/HttpDownloadTransport.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/LoginDialog.h \
    ../Client/NetworkManager.h \
    ../Client/HttpUploadTransport.h \
    ../Client/HttpDownloadTransport.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h \
    ../Common/Message.h \
    ../Common/Protocol.h
