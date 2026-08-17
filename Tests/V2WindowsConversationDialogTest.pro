QT += core gui widgets sql testlib
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
    ../Client/V2WindowsAccountBlockDialog.cpp \
    ../Client/V2WindowsAccountBlockViewModel.cpp \
    ../Client/V2WindowsConversationDirectoryViewModel.cpp \
    ../Client/V2WindowsConversationParticipantViewModel.cpp \
    ../Client/V2WindowsMessageSearchViewModel.cpp \
    ../Client/V2WindowsMessagingPanel.cpp \
    ../Client/V2WindowsForwardTargetDialog.cpp \
    ../Client/V2WindowsMentionComposer.cpp \
    ../Client/V2WindowsMessagingViewModel.cpp \
    ../Client/WindowsLocaleCatalog.cpp \
    ../Client/WindowsLocalePreferenceRepository.cpp \
    ../Client/WindowsLocaleViewModel.cpp \
    ../Client/V2LocalMessageRepository.cpp

HEADERS += \
    ../Client/V2WindowsConversationDialog.h \
    ../Client/V2WindowsAccountBlockDialog.h \
    ../Client/V2WindowsAccountBlockViewModel.h \
    ../Client/V2WindowsConversationDirectoryViewModel.h \
    ../Client/V2WindowsConversationParticipantViewModel.h \
    ../Client/V2WindowsMessageSearchViewModel.h \
    ../Client/V2WindowsMessagingPanel.h \
    ../Client/V2WindowsForwardTargetDialog.h \
    ../Client/V2WindowsMentionComposer.h \
    ../Client/V2WindowsMessagingViewModel.h \
    ../Client/WindowsLocaleCatalog.h \
    ../Client/WindowsLocalePreferenceRepository.h \
    ../Client/WindowsLocaleViewModel.h \
    ../Client/V2LocalMessageRepository.h
