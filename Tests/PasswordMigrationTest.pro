QT += core sql
QT -= gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = PasswordMigrationTest

include(../Common/Libsodium.pri)

INCLUDEPATH += ../Server

SOURCES += \
    PasswordMigrationTest.cpp \
    ../Server/DatabaseManager.cpp \
    ../Server/PasswordHasher.cpp

HEADERS += \
    ../Server/DatabaseManager.h \
    ../Server/PasswordHasher.h
