QT += core
QT -= gui
CONFIG += c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = ChatRoomUpdateLauncher

CHAT_APP_VERSION = $$cat($$PWD/../VERSION, lines)
isEmpty(CHAT_APP_VERSION): error("VERSION is missing or empty")
VERSION = $$CHAT_APP_VERSION

win32 {
    CONFIG += windows
    RC_ICONS = ../Client/resources/app_icon.ico
}
else: CONFIG += console

INCLUDEPATH += . ../Client

SOURCES += \
    main.cpp \
    UpdateLauncherCommand.cpp \
    ../Client/UpdateInstallerTrustVerifier.cpp

HEADERS += \
    UpdateLauncherCommand.h \
    ../Client/UpdateInstallerTrustVerifier.h

win32: LIBS += -lwintrust -lcrypt32
