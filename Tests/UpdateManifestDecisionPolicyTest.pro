QT += core
QT -= gui
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = UpdateManifestDecisionPolicyTest

INCLUDEPATH += ../Client

SOURCES += \
    UpdateManifestDecisionPolicyTest.cpp \
    ../Client/UpdateManifestDecisionPolicy.cpp

HEADERS += \
    ../Client/UpdateManifestDecisionPolicy.h
