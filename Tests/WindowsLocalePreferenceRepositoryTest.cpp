#include "WindowsLocaleCatalog.h"
#include "WindowsAttachmentPresentation.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QCoreApplication>
#include <QDir>
#include <QSettings>
#include <QTemporaryDir>
#include <QDebug>

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    const QString path = QDir(temporary.path()).filePath(QStringLiteral("preferences.ini"));

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        if (repository.load() != WindowsLocale::ZhCn) {
            qCritical() << "missing locale did not use the product default";
            return 1;
        }
        settings.setValue(QStringLiteral("ui/locale"), QStringLiteral("EN-us"));
        settings.sync();
        if (repository.load() != WindowsLocale::ZhCn) {
            qCritical() << "non-exact locale did not fail closed";
            return 1;
        }
        if (!repository.save(WindowsLocale::EnUs)) {
            qCritical() << "locale preference was not persisted";
            return 1;
        }
    }

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel viewModel(&repository);
        if (!viewModel.select(WindowsLocale::EnUs)
                || viewModel.locale() != WindowsLocale::EnUs
                || !viewModel.failure().isEmpty()) {
            qCritical() << "locale view model did not persist exact English";
            return 1;
        }
    }

    {
        QSettings settings(temporary.path(), QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel viewModel(&repository);
        if (viewModel.select(WindowsLocale::EnUs)
                || viewModel.locale() != WindowsLocale::ZhCn
                || repository.load() != WindowsLocale::ZhCn
                || viewModel.failure().isEmpty()) {
            qCritical() << "locale save failure did not preserve current language";
            return 1;
        }
    }

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        if (repository.load() != WindowsLocale::EnUs
                || settings.value(QStringLiteral("ui/locale")).toString()
                    != QStringLiteral("en-US")) {
            qCritical() << "exact locale preference did not survive restart";
            return 1;
        }
        const auto &english = WindowsLocaleCatalog::messages(repository.load());
        if (english.sendMessage != QStringLiteral("Send message")
                || english.bytesUsed != QStringLiteral("%1 / %2 bytes")
                || english.profileTitle != QStringLiteral("Edit profile")
                || english.profileLowBandwidth != QStringLiteral("Low-bandwidth mode")
                || english.profileAvatarChooseTitle
                    != QStringLiteral("Choose avatar image")
                || english.profileAvatarFileFilter
                    != QStringLiteral(
                        "Image files (*.png *.jpg *.jpeg *.bmp *.gif)")
                || english.profileAvatarTooLarge
                    != QStringLiteral(
                        "The cropped avatar exceeds 256 KB; choose a smaller image or crop area")
                || english.profileAvatarUploadFailed
                    != QStringLiteral("Unable to upload avatar")
                || english.profilePasswordFieldsRequired
                    != QStringLiteral("Complete every password field")
                || english.profileNicknameChangedStatus
                    != QStringLiteral("Nickname changed to %1")
                || english.profileNicknameChangeFailed
                    != QStringLiteral("Unable to change nickname")
                || english.profileUserIdChangedDetail
                    != QStringLiteral("User ID changed from %1 to %2")
                || english.profileUserIdChangeFailed
                    != QStringLiteral("Unable to change user ID")
                || english.profileLocalCacheMigrationFailed
                    != QStringLiteral(
                        "Local message-cache migration failed; online-only mode is active")
                || english.loginWindowTitle
                    != QStringLiteral("Qt Chat Room - Sign in")
                || english.registrationSucceeded
                    != QStringLiteral(
                        "Registration succeeded! Switch to the sign-in tab")
                || english.emojiPickerTitle != QStringLiteral("Emoji")
                || english.emojiInsertAccessible
                    != QStringLiteral("Insert emoji %1")
                || english.forwardTitle
                    != QStringLiteral("Forward to another conversation")
                || english.forwardConfirm != QStringLiteral("Forward")
                || english.userInfoTitle != QStringLiteral("User information")
                || english.userInfoAdministrator
                    != QStringLiteral("Administrator")
                || english.avatarCropTitle != QStringLiteral("Crop avatar")
                || english.avatarCropConfirm != QStringLiteral("Confirm")
                || english.roomSettingsTitle != QStringLiteral("Room settings")
                || english.roomDeleteConfirmTitle
                    != QStringLiteral("Confirm deletion")
                || english.roomDeveloperKeyRequired
                    != QStringLiteral("Enter the developer key")
                || english.roomFileManagerTitle != QStringLiteral("File manager")
                || english.roomFileDeleteSelected
                    != QStringLiteral("Delete selected files")
                || english.roomFileCleared != QStringLiteral("Expired/cleared")
                || english.roomPasswordRequiredTitle
                    != QStringLiteral("Password required")
                || english.roomPasswordJoinAction != QStringLiteral("Join")
                || english.roomPasswordStatusTitle
                    != QStringLiteral("Room password status")
                || english.roomPasswordPresent
                    != QStringLiteral(
                        "This room has a password. It cannot be viewed; an administrator can replace it.")
                || english.deviceManagementTitle
                    != QStringLiteral("Signed-in devices")
                || english.deviceManagementRevokeFailed
                    != QStringLiteral("Unable to revoke this device")
                || english.blockDirectoryTitle
                    != QStringLiteral("Privacy and blocked accounts")
                || english.blockDirectoryMutationDisconnected
                    != QStringLiteral(
                        "Connection lost; retry unblocking after reconnecting")
                || english.blockDirectoryRetryableRequestFailed
                    != QStringLiteral(
                        "Blocked-account directory is temporarily unavailable; try again")
                || english.notificationMentionedYou
                    != QStringLiteral("You were mentioned")
                || english.notificationOpenApp
                    != QStringLiteral("Open the chat app to view the message")
                || english.trayApplicationName != QStringLiteral("Chat Room")
                || english.trayShowMainWindow
                    != QStringLiteral("Show main window")
                || english.trayQuit != QStringLiteral("Quit")
                || english.mainWindowTitleForUser
                    != QStringLiteral("Qt Chat Room - %1")
                || english.mainMenuFile != QStringLiteral("&File")
                || english.mainMenuLogout != QStringLiteral("&Sign out")
                || english.mainMenuDevices
                    != QStringLiteral("Signed-in &devices...")
                || english.mainMenuCheckUpdates
                    != QStringLiteral("Check for &updates...")
                || english.mainAboutTitle != QStringLiteral("About")
                || english.mainConnectionStatusAccessible
                    != QStringLiteral("Server connection status")
                || english.mainDisconnected != QStringLiteral("Disconnected")
                || english.mainConnected != QStringLiteral("Connected")
                || english.mainReconnecting
                    != QStringLiteral("Reconnecting (attempt %1)")
                || english.mainComposerEmoji != QStringLiteral("Emoji")
                || english.mainComposerFileTooltip
                    != QStringLiteral("Send a file or image")
                || english.mainComposerPlaceholder
                    != QStringLiteral(
                        "Type a message… (Enter to send, Shift+Enter for a new line)")
                || english.mainComposerSendAccessible
                    != QStringLiteral("Send message")
                || english.mainComposerInsertLineBreak
                    != QStringLiteral("Insert line break")
                || english.mainNavigationRooms != QStringLiteral("Rooms")
                || english.mainNavigationSearchFriends
                    != QStringLiteral("Find friends")
                || english.mainNavigationFriendRequests
                    != QStringLiteral("Friend requests")
                || english.mainNavigationFriendOnline
                    != QStringLiteral("%1 [online]")
                || english.mainNavigationFriendOfflineAccessible
                    != QStringLiteral("%1, offline")
                || english.mainConversationEmptyTitle
                    != QStringLiteral("Select a conversation")
                || english.mainConversationDirectTitle
                    != QStringLiteral("Chat with %1")
                || english.mainConversationAdminTitle
                    != QStringLiteral("%1 [admin]")
                || english.mainConversationMembers
                    != QStringLiteral("Room members")
                || english.mainConversationMemberOffline
                    != QStringLiteral("Offline")
                || english.mainTrayMinimizedBody
                    != QStringLiteral("The app was minimized to the system tray")
                || english.mainLogoutTitle != QStringLiteral("Sign out")
                || english.mainLogoutConfirm
                    != QStringLiteral("Sign out of the current account?")
                || english.mainForcedOfflineTitle
                    != QStringLiteral("Session ended")
                || english.mainCreateRoomTitle
                    != QStringLiteral("Create room")
                || english.mainCreateRoomPrompt
                    != QStringLiteral("Enter a room name:")
                || english.mainRoomSearchTitle != QStringLiteral("Find rooms")
                || english.mainRoomSearchMetadata
                    != QStringLiteral("ID: %1  ·  %2 members")
                || english.mainRoomSearchRequested
                    != QStringLiteral("Requested")
                || english.mainFriendSearchTitle
                    != QStringLiteral("Find friends")
                || english.mainFriendSearchCurrentAccount
                    != QStringLiteral("Current account")
                || english.mainFriendSearchSendRequest
                    != QStringLiteral("Send request")
                || english.mainFriendRequestsTitle
                    != QStringLiteral("Friend requests")
                || english.mainFriendRequestsPending
                    != QStringLiteral("Processing friend request…")
                || english.mainFriendRequestsFailed
                    != QStringLiteral("Unable to process friend request")
                || english.mainFriendRequestsAccepted
                    != QStringLiteral("Accepted")
                || english.mainFriendRequestsRejected
                    != QStringLiteral("Rejected")
                || english.mainFriendViewInfo
                    != QStringLiteral("View information")
                || english.mainFriendRemoveConfirm
                    != QStringLiteral("Remove %1 from your friends?")
                || english.mainFriendRequestSentStatus
                    != QStringLiteral("Friend request sent")
                || english.mainFriendRequestNotificationBody
                    != QStringLiteral("%1 sent you a friend request")
                || english.mainFriendRequestAcceptedByStatus
                    != QStringLiteral("%1 accepted your friend request")
                || english.mainFriendRemovedByStatus
                    != QStringLiteral("%1 removed you from their friends")
                || english.mainLeaveRoomTitle != QStringLiteral("Leave room")
                || english.mainLeaveRoomConfirm
                    != QStringLiteral("Leave room %1?")
                || english.mainLeaveRoomFailed
                    != QStringLiteral("Unable to leave room")
                || english.mainRoomDeleted
                    != QStringLiteral("Room \"%1\" was deleted")
                || english.mainRoomDeletedByAdministrator
                    != QStringLiteral(
                        "Room \"%1\" was deleted by an administrator")
                || english.mainRoomRenamed
                    != QStringLiteral("The room name was updated")
                || english.mainRoomRenameFailed
                    != QStringLiteral("Unable to rename room")
                || english.mainRoomKickSucceeded
                    != QStringLiteral("Removed %1 from the room")
                || english.mainRoomKickedByAdministrator
                    != QStringLiteral(
                        "Administrator %1 removed you from room \"%2\"")
                || english.mainAttachmentCannotDownload
                    != QStringLiteral(
                        "This file expired or was removed and cannot be downloaded.")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::EnUs, QString())
                    != QStringLiteral("File expired or was cleared")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::EnUs, QStringLiteral("retention-policy"))
                    != QStringLiteral("retention-policy")) {
            qCritical() << "English catalog shape changed";
            return 1;
        }
        if (!repository.save(WindowsLocale::ZhCn)) return 1;
        const auto &chinese = WindowsLocaleCatalog::messages(repository.load());
        if (chinese.sendMessage != QStringLiteral("发送消息")
                || chinese.trayApplicationName != QStringLiteral("聊天软件")
                || chinese.trayShowMainWindow != QStringLiteral("显示主窗口")
                || chinese.trayQuit != QStringLiteral("退出")
                || chinese.mainMenuFile != QStringLiteral("文件(&F)")
                || chinese.mainMenuAbout != QStringLiteral("关于(&A)")
                || chinese.mainDisconnected != QStringLiteral("已断开")
                || chinese.mainConnected != QStringLiteral("已连接")
                || chinese.mainComposerEmoji != QStringLiteral("表情")
                || chinese.mainComposerSend != QStringLiteral("发送")
                || chinese.mainNavigationRooms != QStringLiteral("房间")
                || chinese.mainNavigationFriends != QStringLiteral("好友")
                || chinese.mainConversationEmptyTitle
                    != QStringLiteral("请选择一个会话")
                || chinese.mainConversationMembers
                    != QStringLiteral("聊天室成员")
                || chinese.mainTrayMinimizedTitle
                    != QStringLiteral("聊天软件")
                || chinese.mainLogoutTitle != QStringLiteral("注销")
                || chinese.mainCreateRoomTitle
                    != QStringLiteral("创建聊天室")
                || chinese.mainRoomSearchTitle
                    != QStringLiteral("搜索聊天室")
                || chinese.mainFriendSearchTitle
                    != QStringLiteral("搜索好友")
                || chinese.mainFriendRequestsTitle
                    != QStringLiteral("好友申请")
                || chinese.mainFriendRequestsAccepted
                    != QStringLiteral("已接受")
                || chinese.mainFriendRequestsRejected
                    != QStringLiteral("已拒绝")
                || chinese.mainFriendViewInfo
                    != QStringLiteral("查看信息")
                || chinese.mainFriendRemoveConfirm
                    != QStringLiteral("确定要删除好友 %1 吗？")
                || chinese.mainFriendRequestSentStatus
                    != QStringLiteral("好友申请已发送")
                || chinese.mainLeaveRoomTitle
                    != QStringLiteral("退出聊天室")
                || chinese.profileNicknameChangedStatus
                    != QStringLiteral("昵称已修改为：%1")
                || chinese.profileAvatarChooseTitle
                    != QStringLiteral("选择头像图片")
                || chinese.profileAvatarUploadSucceededStatus
                    != QStringLiteral("头像上传成功")
                || chinese.profileUserIdChangedDetail
                    != QStringLiteral("用户 ID 已从 %1 修改为 %2")
                || chinese.profileLocalCacheMigrationFailed
                    != QStringLiteral(
                        "本地消息缓存迁移失败，已切换为在线模式")
                || chinese.mainRoomDeleted
                    != QStringLiteral("聊天室“%1”已被删除")
                || chinese.mainRoomRenamed
                    != QStringLiteral("聊天室名称修改成功")
                || chinese.mainRoomKickSucceeded
                    != QStringLiteral("已将 %1 移出聊天室")
                || chinese.mainAttachmentCannotOpen
                    != QStringLiteral("文件已过期或被清除，无法打开")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::ZhCn, QString())
                    != QStringLiteral("文件已过期或被清除")
                || WindowsLocaleCatalog::code(repository.load())
                    != QStringLiteral("zh-CN")) {
            qCritical() << "Chinese catalog shape changed";
            return 1;
        }
    }

    qInfo() << "[WindowsLocalePreferenceRepositoryTest] PASS";
    return 0;
}
