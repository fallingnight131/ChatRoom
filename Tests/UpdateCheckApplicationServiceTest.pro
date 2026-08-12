QT += core network concurrent
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateCheckApplicationServiceTest

INCLUDEPATH += ../Client
include(../Common/Libsodium.pri)

SOURCES += \
    UpdateCheckApplicationServiceTest.cpp \
    ../Client/UpdateCheckApplicationService.cpp \
    ../Client/UpdateManifestFetchTransport.cpp \
    ../Client/UpdatePreparationApplicationService.cpp \
    ../Client/UpdateInstallerDownloadTransport.cpp \
    ../Client/UpdateInstallerTrustVerifier.cpp \
    ../Client/UpdateManifestApplicationService.cpp \
    ../Client/UpdateManifestSignatureVerifier.cpp \
    ../Client/UpdateManifestDecisionPolicy.cpp \
    ../Client/UpdateStateRepository.cpp

HEADERS += \
    ../Client/UpdateCheckApplicationService.h \
    ../Client/UpdateManifestFetchTransport.h \
    ../Client/UpdatePreparationApplicationService.h \
    ../Client/UpdateInstallerDownloadTransport.h

win32: LIBS += -lwintrust -lcrypt32
