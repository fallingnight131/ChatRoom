QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateManifestApplicationServiceTest

INCLUDEPATH += ../Client
include(../Common/Libsodium.pri)

SOURCES += \
    UpdateManifestApplicationServiceTest.cpp \
    ../Client/UpdateManifestApplicationService.cpp \
    ../Client/UpdateManifestSignatureVerifier.cpp \
    ../Client/UpdateManifestDecisionPolicy.cpp \
    ../Client/UpdateStateRepository.cpp

HEADERS += \
    ../Client/UpdateManifestApplicationService.h \
    ../Client/UpdateManifestSignatureVerifier.h \
    ../Client/UpdateManifestDecisionPolicy.h \
    ../Client/UpdateStateRepository.h
