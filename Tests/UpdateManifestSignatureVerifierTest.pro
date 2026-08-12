QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateManifestSignatureVerifierTest

INCLUDEPATH += ../Client ../Common
include(../Common/Libsodium.pri)

SOURCES += \
    UpdateManifestSignatureVerifierTest.cpp \
    ../Client/UpdateManifestSignatureVerifier.cpp

HEADERS += \
    ../Client/UpdateManifestSignatureVerifier.h
