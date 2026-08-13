QT += core gui widgets sql
CONFIG += console c++17
CONFIG -= app_bundle
TEMPLATE = app
TARGET = V2WindowsConversationDialogTest

macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

INCLUDEPATH += ../Client

SOURCES += \
    V2WindowsConversationDialogTest.cpp \
    ../Client/V2WindowsConversationDialog.cpp \
    ../Client/V2WindowsConversationDirectoryViewModel.cpp \
    ../Client/V2WindowsConversationParticipantViewModel.cpp \
    ../Client/V2WindowsMessagingPanel.cpp \
    ../Client/V2WindowsMentionComposer.cpp \
    ../Client/V2WindowsMessagingViewModel.cpp \
    ../Client/V2LocalMessageRepository.cpp

HEADERS += \
    ../Client/V2WindowsConversationDialog.h \
    ../Client/V2WindowsConversationDirectoryViewModel.h \
    ../Client/V2WindowsConversationParticipantViewModel.h \
    ../Client/V2WindowsMessagingPanel.h \
    ../Client/V2WindowsMentionComposer.h \
    ../Client/V2WindowsMessagingViewModel.h \
    ../Client/V2LocalMessageRepository.h
