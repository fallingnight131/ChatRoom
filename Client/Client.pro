QT += core gui widgets network

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
    TrayManager.cpp

HEADERS += \
    NetworkManager.h \
    LoginDialog.h \
    ChatWindow.h \
    MessageModel.h \
    MessageDelegate.h \
    EmojiPicker.h \
    ThemeManager.h \
    TrayManager.h

RESOURCES += \
    resources/resources.qrc
