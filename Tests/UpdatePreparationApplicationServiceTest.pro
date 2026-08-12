QT += core network concurrent
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdatePreparationApplicationServiceTest

INCLUDEPATH += ../Client
include(../Common/Libsodium.pri)

SOURCES += \
    UpdatePreparationApplicationServiceTest.cpp \
    ../Client/UpdatePreparationApplicationService.cpp \
    ../Client/UpdateInstallerDownloadTransport.cpp \
    ../Client/UpdateInstallerTrustVerifier.cpp \
    ../Client/UpdateManifestApplicationService.cpp \
    ../Client/UpdateManifestSignatureVerifier.cpp \
    ../Client/UpdateManifestDecisionPolicy.cpp \
    ../Client/UpdateStateRepository.cpp

HEADERS += \
    ../Client/UpdatePreparationApplicationService.h \
    ../Client/UpdateInstallerDownloadTransport.h \
    ../Client/UpdateInstallerTrustVerifier.h \
    ../Client/UpdateManifestApplicationService.h \
    ../Client/UpdateManifestSignatureVerifier.h \
    ../Client/UpdateManifestDecisionPolicy.h \
    ../Client/UpdateStateRepository.h

win32: LIBS += -lwintrust -lcrypt32
