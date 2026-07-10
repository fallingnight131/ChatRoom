QT += core sql
QT -= gui

CONFIG += c++17 console
CONFIG -= app_bundle

TARGET = DatabaseSchemaTest

INCLUDEPATH += ../Server

SOURCES += \
    DatabaseSchemaTest.cpp \
    ../Server/DatabaseManager.cpp

HEADERS += \
    ../Server/DatabaseManager.h
