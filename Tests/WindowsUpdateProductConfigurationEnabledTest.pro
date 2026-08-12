QT += core network
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsUpdateProductConfigurationEnabledTest

INCLUDEPATH += ../Client
DEFINES += CHAT_UPDATE_CONFIGURATION_ENABLED=1
DEFINES += CHAT_UPDATE_CHANNEL=\\\"stable\\\"
DEFINES += CHAT_UPDATE_MANIFEST_URL=\\\"https://updates.example.test/windows/stable/manifest.json\\\"
DEFINES += CHAT_UPDATE_PRIMARY_KEY_ID=\\\"windows-update-2026-01\\\"
DEFINES += CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX=\\\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\"

SOURCES += \
    WindowsUpdateProductConfigurationEnabledTest.cpp \
    ../Client/WindowsUpdateProductConfiguration.cpp

HEADERS += \
    ../Client/WindowsUpdateProductConfiguration.h \
    ../Client/UpdateManifestSignatureVerifier.h
