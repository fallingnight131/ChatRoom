QT += core gui widgets network concurrent
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsProfileBandwidthTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client ../Common

SOURCES += \
    WindowsProfileBandwidthTest.cpp \
    ../Client/ProfileDialog.cpp \
    ../Client/NetworkManager.cpp \
    ../Client/HttpUploadTransport.cpp \
    ../Client/HttpDownloadTransport.cpp \
    ../Client/WindowsBandwidthPreferenceRepository.cpp \
    ../Client/WindowsBandwidthViewModel.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/ProfileDialog.h \
    ../Client/NetworkManager.h \
    ../Client/HttpUploadTransport.h \
    ../Client/HttpDownloadTransport.h \
    ../Client/WindowsBandwidthPreferenceRepository.h \
    ../Client/WindowsBandwidthViewModel.h \
    ../Common/Message.h \
    ../Common/Protocol.h
