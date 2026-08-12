QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateInstallerTrustVerifierTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateInstallerTrustVerifierTest.cpp \
    ../Client/UpdateInstallerTrustVerifier.cpp

HEADERS += \
    ../Client/UpdateInstallerTrustVerifier.h

win32: LIBS += -lwintrust -lcrypt32
