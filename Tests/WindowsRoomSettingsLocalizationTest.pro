QT += core gui widgets network concurrent
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsRoomSettingsLocalizationTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client ../Common

SOURCES += \
    WindowsRoomSettingsLocalizationTest.cpp \
    ../Client/RoomSettingsDialog.cpp \
    ../Client/AvatarCropDialog.cpp \
    ../Client/NetworkManager.cpp \
    ../Client/HttpUploadTransport.cpp \
    ../Client/HttpDownloadTransport.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp \
    ../Common/Message.cpp

HEADERS += \
    ../Client/RoomSettingsDialog.h \
    ../Client/AvatarCropDialog.h \
    ../Client/NetworkManager.h \
    ../Client/HttpUploadTransport.h \
    ../Client/HttpDownloadTransport.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h \
    ../Common/Message.h \
    ../Common/Protocol.h
