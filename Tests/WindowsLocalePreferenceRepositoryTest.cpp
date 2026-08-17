#include "WindowsLocaleCatalog.h"
#include "WindowsAttachmentPresentation.h"
#include "WindowsMessagePresentation.h"
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
        const QDate today(2026, 8, 17);
        const QDateTime yesterday(QDate(2026, 8, 16), QTime(9, 5));
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
                || english.roomAvatarChangeSucceeded
                    != QStringLiteral("The room avatar was updated")
                || english.roomAvatarUploadFailed
                    != QStringLiteral("The room avatar could not be uploaded")
                || english.roomLimitsSaveSucceeded
                    != QStringLiteral("The room limits were updated")
                || english.roomCleanupConfirm.arg(3).arg(7).arg(
                       QStringLiteral("12 MB"))
                    != QStringLiteral(
                        "The new limits will clear 3 historical files.\n"
                        "7 files using approximately 12 MB will remain.\n"
                        "Their chat records will remain, but the files will appear expired or cleared.\n"
                        "Continue?")
                || english.roomMemberLimitBelowCurrent.arg(42)
                    != QStringLiteral(
                        "The room currently has 42 members; the limit cannot be lower")
                || english.roomFilesUpdatedBy.arg(QStringLiteral("Alice"))
                    != QStringLiteral("Alice updated the room files")
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
                || english.mainLocalCacheUnavailable
                    != QStringLiteral(
                        "Local message cache is unavailable; online-only mode is active")
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
                || english.mainRoomCreateFailedTitle
                    != QStringLiteral("Unable to create room")
                || english.mainRoomCreateFailed
                    != QStringLiteral("The room could not be created")
                || english.mainRoomJoinFailedTitle
                    != QStringLiteral("Unable to join room")
                || english.mainRoomJoinFailed
                    != QStringLiteral("The room could not be joined")
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
                || english.mainAdministratorHint
                    != QStringLiteral(
                        "Tip: right-click a message or member to use administrator actions")
                || english.mainAdministratorStatusSet.arg(QStringLiteral("Alice"))
                    != QStringLiteral("Updated administrator status for Alice")
                || english.mainMessagesDeleted.arg(3)
                    != QStringLiteral("Deleted 3 messages")
                || english.mainMessagesClearedByAdministrator
                    != QStringLiteral("An administrator cleared message history")
                || english.mainUserKickConfirm.arg(QStringLiteral("Alice"))
                    != QStringLiteral("Remove Alice from the room?")
                || english.mainAttachmentCannotDownload
                    != QStringLiteral(
                        "This file expired or was removed and cannot be downloaded.")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::EnUs, QString())
                    != QStringLiteral("File expired or was cleared")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::EnUs, QStringLiteral("retention-policy"))
                    != QStringLiteral("retention-policy")
                || WindowsMessagePresentation::timestamp(
                       WindowsLocale::EnUs, yesterday, today)
                    != QStringLiteral("Yesterday 09:05")
                || WindowsMessagePresentation::timestampWithDelivery(
                       WindowsLocale::EnUs, yesterday,
                       WindowsMessageDeliveryState::Read, true, true,
                       today)
                    != QStringLiteral("Yesterday 09:05 · Read")
                || WindowsMessagePresentation::transferStatus(
                       WindowsLocale::EnUs, QStringLiteral("1.0 MB"),
                       WindowsMessageTransferState::Uploading, 0.42, false)
                    != QStringLiteral("1.0 MB  Uploading 42%")
                || WindowsMessagePresentation::recalledText(
                       WindowsLocale::EnUs, QStringLiteral("Alice"))
                    != QStringLiteral("Alice recalled a message")
                || english.mainMessageRoomSendFailed
                    != QStringLiteral("Message could not be sent")
                || english.mainMessageDirectSendFailed
                    != QStringLiteral("Direct message could not be sent")
                || english.mainMessageStageFailed
                    != QStringLiteral("Unable to prepare the message for sending")
                || english.mainMessageRoomRequired
                    != QStringLiteral("Join a room before sending a message")
                || english.mainMessageRoomHistoryResumeStopped
                    != QStringLiteral(
                        "Room history synchronization stopped; reopen the conversation to retry")
                || english.mainMessageDirectHistorySyncFailed
                    != QStringLiteral(
                        "Direct-message history could not be synchronized")
                || english.mainMessageRecallFailed
                    != QStringLiteral("The message could not be recalled")
                || english.mainTransferPreparingUpload
                       .arg(QStringLiteral("report.pdf"), QStringLiteral("1.0 MB"))
                    != QStringLiteral("Preparing upload: report.pdf (1.0 MB)")
                || english.mainTransferUploading.arg(42)
                    != QStringLiteral("Uploading 42%…")
                || english.mainTransferHttpDownloadingFile.arg(
                       QStringLiteral("report.pdf"))
                    != QStringLiteral("Downloading report.pdf over HTTP…")
                || english.mainTransferDownloadFailed.arg(
                       QStringLiteral("not found"))
                    != QStringLiteral("Download failed: not found")
                || english.mainTransferPauseUpload
                    != QStringLiteral("Pause upload")
                || english.pendingAttachmentStateFinalizing
                    != QStringLiteral("Waiting for server confirmation")
                || english.pendingAttachmentFailureSourceChanged
                    != QStringLiteral("Source file changed")
                || english.pendingAttachmentRow.arg(
                       QStringLiteral("report.pdf"), QStringLiteral("Room 7"),
                       QStringLiteral("Failed"))
                    != QStringLiteral("report.pdf  ·  Room 7  ·  Failed")
                || english.pendingAttachmentReplaceSource
                    != QStringLiteral("Select source file again")
                || english.cacheDirectoryChanged.arg(QStringLiteral("C:/Cache"))
                    != QStringLiteral("Cache directory changed to: C:/Cache")
                || !english.cacheClearPrompt.contains(
                       QStringLiteral("Drafts and messages that are sending or failed to send are retained."))
                || english.cacheCleared.arg(QStringLiteral("1.0 MB"))
                    != QStringLiteral("Cleared local messages and 1.0 MB of media cache")
                || english.attachmentRoomFileTooLarge.arg(QStringLiteral("2 GB"))
                    != QStringLiteral("The file exceeds this room's 2 GB limit.")
                || english.attachmentFriendTooLarge.arg(
                       QStringLiteral("101 MB"), QStringLiteral("100 MB"))
                    != QStringLiteral(
                        "File size 101 MB exceeds the friend transfer limit of 100 MB.")
                || english.messageMenuOpenFolder
                    != QStringLiteral("Open containing folder")
                || english.messageMenuClearAllConfirm
                    != QStringLiteral(
                        "Clear all chat history?\nThis action cannot be undone.")
                || english.messageMenuDeleteRecent
                    != QStringLiteral("Delete messages from the last N days…")
                || english.messageForwardPartial.arg(3).arg(2)
                    != QStringLiteral("Forwarded to 3 conversations; 2 targets failed")
                || english.messageForwardLegacyTooLarge.arg(QStringLiteral("8 MB"))
                    != QStringLiteral("The older server can forward files up to 8 MB")) {
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
                || chinese.mainLocalCacheUnavailable
                    != QStringLiteral(
                        "本地消息缓存不可用，已切换为在线模式")
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
                || chinese.roomAvatarChangeSucceededTitle
                    != QStringLiteral("修改成功")
                || chinese.roomAvatarUploadFailed
                    != QStringLiteral("上传聊天室头像失败")
                || chinese.roomCleanupConfirmTitle
                    != QStringLiteral("确认清理")
                || chinese.roomCleanupDeveloperKeyRequired
                    != QStringLiteral("未输入开发者秘钥")
                || chinese.roomLimitsSaveFailed
                    != QStringLiteral("无法保存房间限制")
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
                || chinese.mainAdministratorSetFailedTitle
                    != QStringLiteral("设置管理员失败")
                || chinese.mainMessagesDeleteFailed
                    != QStringLiteral("无法删除消息")
                || chinese.mainUserGiveUpAdministrator
                    != QStringLiteral("放弃管理员权限")
                || chinese.mainUserKick
                    != QStringLiteral("移出聊天室")
                || chinese.mainAttachmentCannotOpen
                    != QStringLiteral("文件已过期或被清除，无法打开")
                || WindowsAttachmentPresentation::unavailableText(
                       WindowsLocale::ZhCn, QString())
                    != QStringLiteral("文件已过期或被清除")
                || WindowsMessagePresentation::timestamp(
                       WindowsLocale::ZhCn, yesterday, today)
                    != QStringLiteral("昨天 09:05")
                || WindowsMessagePresentation::transferStatus(
                       WindowsLocale::ZhCn, QStringLiteral("1.0 MB"),
                       WindowsMessageTransferState::Paused, 0.42, false)
                    != QStringLiteral("1.0 MB  已暂停 42%")
                || chinese.mainMessageRoomSendFailed
                    != QStringLiteral("消息发送失败")
                || chinese.mainMessageDirectHistoryResumeStopped
                    != QStringLiteral(
                        "好友记录续传已停止，可重新进入会话重试")
                || chinese.mainMessageRecallFailedTitle
                    != QStringLiteral("撤回失败")
                || chinese.mainTransferPreparingUpload
                       .arg(QStringLiteral("report.pdf"), QStringLiteral("1.0 MB"))
                    != QStringLiteral("准备上传：report.pdf（1.0 MB）")
                || chinese.mainTransferUploadPaused.arg(42)
                    != QStringLiteral("上传已暂停 42%")
                || chinese.mainTransferCacheFailed.arg(
                       QStringLiteral("report.pdf"))
                    != QStringLiteral("文件缓存失败：report.pdf")
                || chinese.mainTransferDownloadFile
                    != QStringLiteral("下载文件")
                || chinese.pendingAttachmentStateAuthorization
                    != QStringLiteral("等待授权")
                || chinese.pendingAttachmentFailureFinalizeTimeout
                    != QStringLiteral("服务器确认超时")
                || chinese.pendingAttachmentCancelConfirm
                    != QStringLiteral("确定不再发送这个文件吗？")
                || chinese.cacheChooseDirectory
                    != QStringLiteral("选择缓存目录")
                || !chinese.cacheClearPrompt.contains(
                       QStringLiteral("草稿、发送中和发送失败的消息不会被删除。"))
                || chinese.attachmentSelectImage
                    != QStringLiteral("选择图片")
                || chinese.attachmentImageTooLarge.arg(QStringLiteral("8 MB"))
                    != QStringLiteral("图片大小不能超过 8 MB。")
                || chinese.messageMenuRetrySend
                    != QStringLiteral("重试发送")
                || chinese.messageMenuAdministrator
                    != QStringLiteral("管理员操作")
                || chinese.messageForwardTargetLimit.arg(5)
                    != QStringLiteral("一次最多转发到 5 个会话")
                || WindowsLocaleCatalog::code(repository.load())
                    != QStringLiteral("zh-CN")) {
            qCritical() << "Chinese catalog shape changed";
            return 1;
        }
    }

    qInfo() << "[WindowsLocalePreferenceRepositoryTest] PASS";
    return 0;
}
