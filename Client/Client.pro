QT += core gui widgets network multimedia sql concurrent

CONFIG += c++17

TARGET = ChatClient

CHAT_APP_VERSION = $$cat($$PWD/../VERSION, lines)
isEmpty(CHAT_APP_VERSION): error("VERSION is missing or empty")
VERSION = $$CHAT_APP_VERSION
DEFINES += CHAT_APP_VERSION=\\\"$$CHAT_APP_VERSION\\\"

equals(CHAT_UPDATE_ENABLED, 1) {
    isEmpty(CHAT_UPDATE_CHANNEL): error("CHAT_UPDATE_CHANNEL is required")
    isEmpty(CHAT_UPDATE_MANIFEST_URL): error("CHAT_UPDATE_MANIFEST_URL is required")
    isEmpty(CHAT_UPDATE_PRIMARY_KEY_ID): error("CHAT_UPDATE_PRIMARY_KEY_ID is required")
    isEmpty(CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX): error("CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX is required")
    DEFINES += CHAT_UPDATE_CONFIGURATION_ENABLED=1
    DEFINES += CHAT_UPDATE_CHANNEL=\\\"$$CHAT_UPDATE_CHANNEL\\\"
    DEFINES += CHAT_UPDATE_MANIFEST_URL=\\\"$$CHAT_UPDATE_MANIFEST_URL\\\"
    DEFINES += CHAT_UPDATE_PRIMARY_KEY_ID=\\\"$$CHAT_UPDATE_PRIMARY_KEY_ID\\\"
    DEFINES += CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX=\\\"$$CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX\\\"
    !isEmpty(CHAT_UPDATE_SECONDARY_KEY_ID) {
        isEmpty(CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX): error("CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX is required")
        DEFINES += CHAT_UPDATE_SECONDARY_KEY_ID=\\\"$$CHAT_UPDATE_SECONDARY_KEY_ID\\\"
        DEFINES += CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX=\\\"$$CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX\\\"
    } else:!isEmpty(CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX) {
        error("CHAT_UPDATE_SECONDARY_KEY_ID is required")
    }
} else:!isEmpty(CHAT_UPDATE_ENABLED) {
    error("CHAT_UPDATE_ENABLED must be exactly 1 when supplied")
}

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
    DeviceManagementViewModel.cpp \
    DeviceManagementDialog.cpp \
    V1HistoryPageAdapter.cpp \
    UpdateManifestSignatureVerifier.cpp \
    UpdateManifestDecisionPolicy.cpp \
    UpdateInstallerTrustVerifier.cpp \
    UpdateStateRepository.cpp \
    UpdateManifestApplicationService.cpp \
    UpdateInstallerDownloadTransport.cpp \
    UpdatePreparationApplicationService.cpp \
    WindowsClientInstanceGuard.cpp \
    UpdateManifestFetchTransport.cpp \
    UpdateCheckApplicationService.cpp \
    WindowsUpdateHandoffApplicationService.cpp \
    UpdateLauncherResult.cpp \
    UpdateLifecycleRepository.cpp \
    WindowsUpdateInstallCoordinator.cpp \
    WindowsUpdateRuntimePaths.cpp \
    WindowsUpdateStartupService.cpp \
    WindowsUpdateProductConfiguration.cpp \
    WindowsUpdateTrustDiagnostic.cpp \
    WindowsUpdateController.cpp \
    AvatarCropDialog.cpp \
    ForwardSelectDialog.cpp \
    RoomSettingsDialog.cpp \
    RoomFileManagerDialog.cpp \
    RoomPasswordPromptDialog.cpp \
    ProfileDialog.cpp \
    UserInfoDialog.cpp \
    WindowsBandwidthPolicy.cpp \
    WindowsBandwidthPreferenceRepository.cpp \
    WindowsBandwidthViewModel.cpp \
    WindowsLocaleCatalog.cpp \
    WindowsLocalePreferenceRepository.cpp \
    WindowsLocaleViewModel.cpp

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
    DeviceManagementViewModel.h \
    DeviceManagementDialog.h \
    V1HistoryPageAdapter.h \
    UpdateManifestSignatureVerifier.h \
    UpdateManifestDecisionPolicy.h \
    UpdateInstallerTrustVerifier.h \
    UpdateStateRepository.h \
    UpdateManifestApplicationService.h \
    UpdateInstallerDownloadTransport.h \
    UpdatePreparationApplicationService.h \
    WindowsClientInstanceGuard.h \
    UpdateManifestFetchTransport.h \
    UpdateCheckApplicationService.h \
    WindowsUpdateHandoffApplicationService.h \
    UpdateLauncherResult.h \
    UpdateLifecycleRepository.h \
    WindowsUpdateInstallCoordinator.h \
    WindowsUpdateRuntimePaths.h \
    WindowsUpdateStartupService.h \
    WindowsUpdateProductConfiguration.h \
    WindowsUpdateTrustDiagnostic.h \
    WindowsUpdateController.h \
    AvatarCropDialog.h \
    ForwardSelectDialog.h \
    RoomSettingsDialog.h \
    RoomFileManagerDialog.h \
    RoomPasswordPromptDialog.h \
    ProfileDialog.h \
    UserInfoDialog.h \
    WindowsBandwidthPolicy.h \
    WindowsBandwidthPreferenceRepository.h \
    WindowsBandwidthViewModel.h \
    WindowsLocaleCatalog.h \
    WindowsLocalePreferenceRepository.h \
    WindowsLocaleViewModel.h

RESOURCES += \
    resources/resources.qrc

RC_ICONS = resources/app_icon.ico

win32: LIBS += -lole32 -luuid -lgdi32 -lwintrust -lcrypt32
