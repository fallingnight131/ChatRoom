QT += core gui widgets network multimedia

CONFIG += c++17

TARGET = ChatClient

include(../Common/Common.pri)

SOURCES += \
    main.cpp \
    NetworkManager.cpp \
    LoginDialog.cpp \
    ChatWindow.cpp \
    MessageModel.cpp \
    MessageDelegate.cpp \
    EmojiPicker.cpp \
    ThemeManager.cpp \
    TrayManager.cpp \
    FileCache.cpp \
    AvatarCropDialog.cpp \
    RoomSettingsDialog.cpp \
    ProfileDialog.cpp

HEADERS += \
    NetworkManager.h \
    LoginDialog.h \
    ChatWindow.h \
    MessageModel.h \
    MessageDelegate.h \
    EmojiPicker.h \
    ThemeManager.h \
    TrayManager.h \
    FileCache.h \
    AvatarCropDialog.h \
    RoomSettingsDialog.h \
    ProfileDialog.h

RESOURCES += \
    resources/resources.qrc

RC_ICONS = resources/app_icon.ico

win32: LIBS += -lole32 -luuid -lgdi32
