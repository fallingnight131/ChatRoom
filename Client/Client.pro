QT += core gui widgets network multimedia sql

CONFIG += c++17

TARGET = ChatClient

CHAT_APP_VERSION = $$cat($$PWD/../VERSION, lines)
isEmpty(CHAT_APP_VERSION): error("VERSION is missing or empty")
VERSION = $$CHAT_APP_VERSION
DEFINES += CHAT_APP_VERSION=\\\"$$CHAT_APP_VERSION\\\"

include(../Common/Common.pri)
include(../Common/Libsodium.pri)

SOURCES += \
    main.cpp \
    HttpUploadTransport.cpp \
    HttpDownloadTransport.cpp \
    NetworkManager.cpp \
    LoginDialog.cpp \
    ChatWindow.cpp \
    MessageModel.cpp \
    MessageDelegate.cpp \
    EmojiPicker.cpp \
    ThemeManager.cpp \
    TrayManager.cpp \
    FileCache.cpp \
    LocalConversationRepository.cpp \
    AttachmentOutboxService.cpp \
    OutgoingMessageService.cpp \
    ConversationSyncService.cpp \
    V1HistoryPageAdapter.cpp \
    UpdateManifestSignatureVerifier.cpp \
    AvatarCropDialog.cpp \
    ForwardSelectDialog.cpp \
    RoomSettingsDialog.cpp \
    RoomFileManagerDialog.cpp \
    ProfileDialog.cpp \
    UserInfoDialog.cpp

HEADERS += \
    NetworkManager.h \
    HttpUploadTransport.h \
    HttpDownloadTransport.h \
    LoginDialog.h \
    ChatWindow.h \
    MessageModel.h \
    MessageDelegate.h \
    EmojiPicker.h \
    ThemeManager.h \
    TrayManager.h \
    FileCache.h \
    LocalConversationRepository.h \
    AttachmentOutboxService.h \
    OutgoingMessageService.h \
    ConversationSyncService.h \
    V1HistoryPageAdapter.h \
    UpdateManifestSignatureVerifier.h \
    AvatarCropDialog.h \
    ForwardSelectDialog.h \
    RoomSettingsDialog.h \
    RoomFileManagerDialog.h \
    ProfileDialog.h \
    UserInfoDialog.h

RESOURCES += \
    resources/resources.qrc

RC_ICONS = resources/app_icon.ico

win32: LIBS += -lole32 -luuid -lgdi32
