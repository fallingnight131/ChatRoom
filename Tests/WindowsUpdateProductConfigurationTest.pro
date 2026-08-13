QT += core network
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = WindowsUpdateProductConfigurationTest

INCLUDEPATH += ../Client

SOURCES += \
    WindowsUpdateProductConfigurationTest.cpp \
    ../Client/WindowsUpdateProductConfiguration.cpp \
    ../Client/WindowsUpdateTrustDiagnostic.cpp

HEADERS += \
    ../Client/WindowsUpdateProductConfiguration.h \
    ../Client/WindowsUpdateTrustDiagnostic.h \
    ../Client/UpdateManifestSignatureVerifier.h
