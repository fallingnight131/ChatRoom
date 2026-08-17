#!/usr/bin/env python3
"""Lock the default-off Windows V2 device-management product composition."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, markers: tuple[str, ...], source: str) -> None:
    for marker in markers:
        if marker not in text:
            raise AssertionError(f"{source} omits Windows V2 composition marker: {marker}")


def main() -> int:
    cmake = (ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
    main_source = (ROOT / "Client/main.cpp").read_text(encoding="utf-8")
    login = (ROOT / "Client/LoginDialog.cpp").read_text(encoding="utf-8")
    window = (ROOT / "Client/ChatWindow.cpp").read_text(encoding="utf-8")
    message_delegate = (ROOT / "Client/MessageDelegate.cpp").read_text(
        encoding="utf-8"
    )
    attachment_presentation = (
        ROOT / "Client/WindowsAttachmentPresentation.cpp"
    ).read_text(encoding="utf-8")
    message_presentation = (
        ROOT / "Client/WindowsMessagePresentation.cpp"
    ).read_text(encoding="utf-8")
    controller = (ROOT / "Client/WindowsDeviceManagementController.cpp").read_text(
        encoding="utf-8"
    )
    block_controller = (ROOT / "Client/WindowsAccountBlockController.cpp").read_text(
        encoding="utf-8"
    )
    messaging_controller = (ROOT / "Client/WindowsV2MessagingController.cpp").read_text(
        encoding="utf-8"
    )
    session_protocol = (ROOT / "Client/V2WindowsSessionProtocolClient.cpp").read_text(
        encoding="utf-8"
    )
    v2_transport = (ROOT / "Client/V2WindowsDeviceManagementTransport.cpp").read_text(
        encoding="utf-8"
    )
    panel = (ROOT / "Client/V2WindowsMessagingPanel.cpp").read_text(encoding="utf-8")
    profile = (ROOT / "Client/ProfileDialog.cpp").read_text(encoding="utf-8")
    room_settings = (ROOT / "Client/RoomSettingsDialog.cpp").read_text(
        encoding="utf-8"
    )
    room_files = (ROOT / "Client/RoomFileManagerDialog.cpp").read_text(
        encoding="utf-8"
    )
    room_password = (ROOT / "Client/RoomPasswordPromptDialog.cpp").read_text(
        encoding="utf-8"
    )
    room_search = (ROOT / "Client/RoomSearchDialog.cpp").read_text(encoding="utf-8")
    friend_search = (ROOT / "Client/FriendSearchDialog.cpp").read_text(
        encoding="utf-8"
    )
    friend_requests = (ROOT / "Client/FriendRequestsDialog.cpp").read_text(
        encoding="utf-8"
    )
    device_dialog = (ROOT / "Client/DeviceManagementDialog.cpp").read_text(
        encoding="utf-8"
    )
    notification_policy = (ROOT / "Client/WindowsMessageNotificationPolicy.cpp").read_text(
        encoding="utf-8"
    )
    notification_presenter = (
        ROOT / "Client/WindowsMessageNotificationPresenter.cpp"
    ).read_text(encoding="utf-8")
    tray = (ROOT / "Client/TrayManager.cpp").read_text(encoding="utf-8")
    connection_status = (
        ROOT / "Client/WindowsConnectionStatusViewModel.cpp"
    ).read_text(encoding="utf-8")
    bandwidth_policy = (ROOT / "Client/WindowsBandwidthPolicy.cpp").read_text(
        encoding="utf-8"
    )
    conversation_dialog = (ROOT / "Client/V2WindowsConversationDialog.cpp").read_text(
        encoding="utf-8"
    )
    block_directory_dialog = (
        ROOT / "Client/V2WindowsAccountBlockDirectoryDialog.cpp"
    ).read_text(encoding="utf-8")
    qmake = (ROOT / "Client/Client.pro").read_text(encoding="utf-8")

    for source, text in (
        ("CMakeLists.txt", cmake),
        ("Client/ChatWindow.cpp", window),
        ("Client/WindowsV2MessagingController.cpp", messaging_controller),
        ("Client/V2WindowsMessagingPanel.cpp", panel),
        ("Client/V2WindowsDeviceManagementTransport.cpp", v2_transport),
        ("Client/Client.pro", qmake),
    ):
        if "V2WindowsAttachmentProtocolClient" in text:
            raise AssertionError(
                f"{source} must not activate Windows V2 attachments before provider gates"
            )

    require(cmake, (
        "CHAT_WINDOWS_V2_PRODUCT_AVAILABLE=1",
        "Client/WindowsDeviceManagementController.cpp",
        "chatroom_windows_v2_transport",
    ), "CMakeLists.txt")
    require(main_source, (
        "WindowsLocalePreferenceRepository localeRepository",
        "WindowsLocaleViewModel localeViewModel",
        "LoginDialog(nullptr, &localeViewModel)",
        "ChatWindow(nullptr, &localeViewModel)",
        "WindowsV2ProductConfiguration::fromBuild()",
        "WindowsDeviceIdentityRepository",
        "takePasswordUtf8()",
        "configureDeviceManagement(",
        "v2Configuration.messageForwardingEnabled",
        "v2Configuration.messageSearchEnabled",
        "v2Configuration.notificationsEnabled",
        "v2Configuration.accountBlockingEnabled",
        "password.fill('\\0')",
        "&ChatWindow::logoutRequested",
        "showLoginDialog();",
        "QPointer<LoginDialog> activeLoginDialog",
        "if (activeLoginDialog)",
        "copy.mainForcedOfflineTitle, reason",
    ), "Client/main.cpp")
    if "用户主动注销" in main_source or "用户主动注销" in window:
        raise AssertionError("local logout must not use localized text as control state")
    require(login, (
        "QByteArray LoginDialog::takePasswordUtf8()",
        "m_loginPass->clear()",
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_localeViewModel->select",
        "m_loginStatusKind",
        "m_registerStatusKind",
    ),
            "Client/LoginDialog.cpp")
    require(window, (
        "m_deviceManagementAction = m_settingsMenu->addAction(",
        "m_deviceManagementAction->setVisible(false)",
        "m_deviceManagementController->start()",
        "m_deviceManagementController->stop()",
        "DeviceManagementDialog",
        "m_v2MessageForwardingEnabled,",
        "messageSearchViewModel(),",
        "WindowsMessageNotificationPresenter",
        "accountBlockViewModel()",
        "accountBlockDirectoryViewModel()",
        "V2WindowsAccountBlockDirectoryDialog",
        "TrayManager::notificationActivated",
        "WindowsLocalePreferenceRepository",
        "WindowsLocaleViewModel",
        "m_windowsLocaleViewModel->locale()",
        "WindowsBandwidthPreferenceRepository",
        "WindowsBandwidthViewModel",
        "WindowsAvatarRequestCoordinator",
        "requestAvatar(m_username, true)",
        "new EmojiPicker(this, m_windowsLocaleViewModel)",
        "roomTargets, friendTargets, this, m_windowsLocaleViewModel",
        "username, displayName, avatar, role, this, m_windowsLocaleViewModel",
        "AvatarCropDialog dlg(img, this, m_windowsLocaleViewModel)",
        "maxMembers, this, m_windowsLocaleViewModel",
        "this, m_windowsLocaleViewModel);",
        "new RoomPasswordPromptDialog(",
        "m_roomPasswordPromptDialog->close()",
        "if (m_roomPasswordPromptDialog == dialog)",
        "copy.roomPasswordSetStatus",
        "copy.roomPasswordStatusFailedTitle, error",
        "m_deviceManagementController->viewModel(), this,",
        "m_windowsLocaleViewModel);",
        "}, 256, m_windowsLocaleViewModel);",
        "new TrayManager(this, m_windowsLocaleViewModel)",
        "WindowsLocaleViewModel::changed,",
        "&ChatWindow::refreshWindowChrome",
        "copy.mainWindowTitleForUser.arg(m_displayName)",
        "m_deviceManagementAction->setText(copy.mainMenuDevices)",
        "m_v2ConversationAction->setText(copy.mainMenuV2Preview)",
        "m_accountBlockDirectoryAction->setText(copy.mainMenuBlockedAccounts)",
        "QMessageBox::about(this, copy.mainAboutTitle, copy.mainAboutBody)",
        "m_connectionStatusViewModel->setConnected()",
        "m_connectionStatusViewModel->setDisconnected()",
        "m_connectionStatusViewModel->setReconnecting(attempt)",
        "statusBar()->addWidget(m_statusLabel, 1)",
        "statusBar()->addPermanentWidget(m_connectionStatusLabel)",
        "copy.mainConnectionStatusAccessible",
        "copy.mainReconnecting.arg(",
        "&ChatWindow::refreshComposerText",
        "copy.mainComposerEmojiAccessible",
        "copy.mainComposerFileAccessible",
        "copy.mainComposerInputAccessible",
        "copy.mainComposerSendAccessible",
        ").mainComposerInsertLineBreak",
        "&ChatWindow::refreshNavigationText",
        "copy.mainNavigationRoomsAccessible",
        "copy.mainNavigationFriendListAccessible",
        "copy.mainNavigationFriendOnline.arg(identity)",
        "copy.mainNavigationFriendOfflineAccessible.arg(identity)",
        "item->setData(Qt::UserRole + 3, isOnline)",
        "generateDefaultAvatar(identity, qHash(username))",
        "m_avatarPreview->pixmap().isNull()",
        "&ChatWindow::refreshConversationShellText",
        "copy.mainConversationDirectTitle.arg(identity)",
        "copy.mainConversationAdminTitle.arg(item->text())",
        "copy.mainConversationMemberOfflineAccessible.arg(displayName)",
        "copy.mainTrayMinimizedTitle, copy.mainTrayMinimizedBody",
        "copy.mainLogoutTitle, copy.mainLogoutConfirm",
        "emit logoutRequested()",
        "dialog.setWindowTitle(copy.mainCreateRoomTitle)",
        "dialog.setLabelText(copy.mainCreateRoomPrompt)",
        "dialog.setAccessibleName(copy.mainCreateRoomAccessible)",
        "RoomSearchDialog dialog(m_windowsLocaleViewModel, this)",
        "&RoomSearchDialog::searchRequested",
        "&RoomSearchDialog::joinRequested",
        "dialog.showResults(results)",
        "FriendSearchDialog dialog(m_windowsLocaleViewModel, this)",
        "&FriendSearchDialog::friendRequestRequested",
        "dialog.updateAvatar(username, avatar)",
        "FriendRequestsDialog dialog(m_windowsLocaleViewModel, this)",
        "&FriendRequestsDialog::acceptRequested",
        "&FriendRequestsDialog::rejectRequested",
        "dialog.resolveAccept(success, error)",
        "dialog.resolveReject(success, error)",
        "dialog.setRequests(pending)",
        "copy.mainFriendViewInfo",
        "activeCopy.mainFriendRemoveConfirm.arg(friendUsername)",
        "copy.mainFriendRequestNotificationBody.arg(identity)",
        "copy.mainFriendRequestAcceptedByStatus.arg(identity)",
        "copy.mainFriendRemovedByStatus.arg(identity)",
        "copy.mainLeaveRoomConfirm.arg(roomName)",
        "m_statusLabel->setText(copy.mainLeaveRoomFailed)",
        "copy.mainRoomDeleted.arg(roomName)",
        "copy.mainRoomDeletedByAdministrator.arg(roomName)",
        "error.isEmpty() ? copy.roomDeleteFailedTitle : error",
        "copy.mainRoomRenamed",
        "copy.mainRoomRenameFailed : error",
        "copy.mainRoomKickSucceeded.arg(username)",
        "copy.mainRoomKickFailed : error",
        "copy.mainRoomKickedByAdministrator.arg(operatorName, roomName)",
        "copy.profileNicknameChangedStatus.arg(m_displayName)",
        "copy.profileNicknameChangeFailed : error",
        "new MessageDelegate(m_windowsLocaleViewModel, m_messageView)",
        "copy.mainAttachmentCannotDownload",
        "copy.mainAttachmentCannotOpen",
        "copy.mainAttachmentCannotForward",
        "copy.profileLocalCacheMigrationFailed",
        "copy.profileUserIdChangedStatus.arg(newUid)",
        "copy.profileUserIdChangedDetail.arg(oldUid, newUid)",
        "copy.profileUserIdChangeFailed : error",
        "m_localRepository->copyAccountTo(",
        "NetworkManager::instance()->setCredentials(",
        "copy.profileAvatarChooseTitle",
        "copy.profileAvatarFileFilter",
        "copy.profileAvatarLoadFailed",
        "copy.profileAvatarTooLarge",
        "copy.profileAvatarUploadSucceededStatus",
        "copy.profileAvatarUploadFailed : error",
        ").mainTransferPreparingUpload",
        "copy.mainTransferUploadFailedTitle",
        "copy.mainTransferHttpUploadStartFailed",
        "copy.mainTransferPauseUpload",
        "copy.mainTransferDownloadFile",
        "copy.mainTransferFriendSendRetryableFailure",
    ), "Client/ChatWindow.cpp")
    transfer_surface = window[
        window.index("void ChatWindow::onFileDownloadReady("):
        window.index("// ==================== 消息撤回")
    ]
    friend_transfer_surface = window[
        window.index("void ChatWindow::onFriendFileUploadStartResponse("):
        window.index("void ChatWindow::onSendFriendFile(")
    ]
    embedded_transfer_copy = (
        '"\u4e0a\u4f20\u5931\u8d25"', '"\u6b63\u5728\u901a\u8fc7 HTTP \u4e0a\u4f20..."',
        '"\u4e0a\u4f20\u4e2d %1%..."', '"\u6587\u4ef6\u5df2\u4e0a\u4f20\uff0c\u6b63\u5728\u540c\u6b65\u5230\u4e91\u7aef..."',
        '"\u4e0b\u8f7d\u5df2\u6682\u505c"', '"\u4e0b\u8f7d\u4e2d %1%..."',
        '"\u6587\u4ef6\u7f13\u5b58\u5931\u8d25: %1"', '"\u6682\u505c\u4e0a\u4f20"',
        '"\u6062\u590d\u4e0b\u8f7d"', '"\u4e0b\u8f7d\u6587\u4ef6"',
    )
    if any(copy in transfer_surface or copy in friend_transfer_surface
           for copy in embedded_transfer_copy):
        raise AssertionError(
            "Windows file-transfer activity must project from the locale catalog"
        )
    if 'm_statusLabel->text().contains("\u6587\u4ef6")' in window \
            or 'm_statusLabel->text() == "\u6587\u4ef6\u4e0a\u4f20\u5b8c\u6210"' in window:
        raise AssertionError(
            "file-transfer control flow must not depend on localized status copy"
        )
    pending_attachment_surface = window[
        window.index("void ChatWindow::showPendingAttachments("):
        window.index("#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE",
                     window.index("void ChatWindow::showPendingAttachments("))
    ]
    require(pending_attachment_surface, (
        "copy.pendingAttachmentStoreUnavailable",
        'failureCode == QStringLiteral("SOURCE_UNAVAILABLE")',
        'failureCode == QStringLiteral("SOURCE_CHANGED")',
        'failureCode == QStringLiteral("FINALIZE_TIMEOUT")',
        "const QString selectedId = list->currentItem()",
        "command.clientMessageId == selectedId",
        "copy.pendingAttachmentFailureDiagnostic.arg(",
        "Qt::convertFromPlainText(tooltip)",
        "activeQtLocale(m_windowsLocaleViewModel)",
        "copy.pendingAttachmentRetryFailed",
        "copy.pendingAttachmentReplaceFailed",
        "&WindowsLocaleViewModel::changed",
        "refreshPresentation();",
    ), "Client/ChatWindow.cpp pending attachment surface")
    if any(copy in pending_attachment_surface for copy in (
        'QStringLiteral("\u5f85\u53d1\u9001\u6587\u4ef6")',
        'QStringLiteral("\u7b49\u5f85\u6388\u6743")',
        'QStringLiteral("\u7b49\u5f85\u670d\u52a1\u5668\u786e\u8ba4")',
        'QStringLiteral("\u91cd\u65b0\u9009\u62e9\u6e90\u6587\u4ef6")',
        "QMessageBox::warning(this, QStringLiteral",
    )):
        raise AssertionError(
            "pending attachment tasks must project stable state through the locale catalog"
        )
    if any(exposure in pending_attachment_surface for exposure in (
        "QMessageBox::warning(this, copy.pendingAttachmentRetryTitle,\n"
        "                                 m_attachmentOutboxService->lastError())",
        "QMessageBox::warning(this, copy.pendingAttachmentReplaceTitle,\n"
        "                                 m_attachmentOutboxService->lastError())",
    )):
        raise AssertionError(
            "repository diagnostics must not be exposed as pending-task user copy"
        )
    cache_surface = window[
        window.index("void ChatWindow::onChangeCacheDir("):
        window.index("// ==================== 好友系统")
    ]
    require(cache_surface, (
        "copy.cacheChooseDirectory",
        "copy.cacheDirectoryChanged.arg(newDir)",
        "copy.cacheClearPrompt.arg(m_username, sizeText)",
        "activeQtLocale(",
        "copy.cacheClearFailed",
        "copy.cacheCleared.arg(sizeText)",
        "m_conversationSyncService->clearCachedMessages()",
        "requestCurrentRoomResume();",
        "requestCurrentFriendResume();",
    ), "Client/ChatWindow.cpp cache management surface")
    if any(copy in cache_surface for copy in (
        '"\u9009\u62e9\u7f13\u5b58\u76ee\u5f55"', '"\u6e05\u9664\u7f13\u5b58"',
        'QString("\u5f53\u524d\u8d26\u53f7', 'QStringLiteral("\u6e05\u9664\u7f13\u5b58\u5931\u8d25")',
        'QString("\u5df2\u6e05\u9664\u672c\u5730\u6d88\u606f',
    )):
        raise AssertionError(
            "cache management must project copy and data sizes from the active locale"
        )
    room_attachment_selection = window[
        window.index("void ChatWindow::onSendFile("):
        window.index("void ChatWindow::onFileNotify(")
    ]
    friend_attachment_selection = window[
        window.index("void ChatWindow::onSendFriendFile("):
        window.index("void ChatWindow::onFriendRecallResponse(")
    ]
    require(room_attachment_selection, (
        "copy.attachmentSelectFile",
        "copy.attachmentFileTooLarge.arg(",
        "copy.attachmentRoomFileTooLarge.arg(",
        "copy.attachmentSelectImage",
        "copy.attachmentImageFilter",
        "copy.attachmentRoomImageTooLarge.arg(",
        "activeQtLocale(m_windowsLocaleViewModel)",
        "startChunkedUpload(filePath);",
    ), "Client/ChatWindow.cpp room attachment selection")
    require(friend_attachment_selection, (
        ").attachmentSendFile",
        "copy.attachmentSendImage",
        "copy.attachmentImageFilesFilter",
        "copy.attachmentFriendTooLarge.arg(",
        "locale.formattedDataSize(Protocol::MAX_FRIEND_FILE)",
        "stageAttachment(AttachmentOutboxService::directTarget(",
    ), "Client/ChatWindow.cpp friend attachment selection")
    if any(copy in room_attachment_selection + friend_attachment_selection
           for copy in (
               '"\u9009\u62e9\u6587\u4ef6"', '"\u9009\u62e9\u56fe\u7247"',
               '"\u53d1\u9001\u6587\u4ef6"', '"\u53d1\u9001\u56fe\u7247"',
               '"\u6587\u4ef6\u8fc7\u5927"', 'QString("\u6587\u4ef6\u5927\u5c0f',
               'QString("\u56fe\u7247\u5927\u5c0f', "QLocale().formattedDataSize",
           )):
        raise AssertionError(
            "attachment selection and local limits must use the active Windows locale"
        )
    message_menu_surface = window[
        window.index("void ChatWindow::onMessageContextMenu("):
        window.index("int ChatWindow::forwardFileWithLegacyProtocol(")
    ]
    require(message_menu_surface, (
        "copy.messageMenuViewUser",
        "copy.messageMenuRetrySend",
        "copy.messageMenuOpenFile",
        "copy.messageMenuOpenFolder",
        "copy.messageMenuRecall",
        "copy.messageMenuCopyText",
        "copy.messageMenuForward",
        "copy.messageMenuDelete",
        "copy.messageMenuAdministrator",
        "activeCopy.messageMenuClearAllConfirm",
        "activeCopy.messageMenuDeleteBeforePrompt",
        "activeCopy.messageMenuDeleteRecentPrompt",
        'data["mode"] = QStringLiteral("selected")',
        'data["mode"] = QStringLiteral("all")',
        'data["mode"] = QStringLiteral("before")',
        'data["mode"] = QStringLiteral("after")',
        'data["clientOperationId"] = QUuid::createUuid()',
    ), "Client/ChatWindow.cpp message context menu")
    if any(copy in message_menu_surface for copy in (
        '"\u67e5\u770b\u7528\u6237\u4fe1\u606f"', '"\u91cd\u8bd5\u53d1\u9001"',
        '"\u6253\u5f00\u6587\u4ef6"', '"\u6253\u5f00\u6240\u5728\u6587\u4ef6\u5939"',
        '"\u64a4\u56de\u6d88\u606f"', '"\u590d\u5236\u6587\u672c"',
        '"\u8f6c\u53d1\u6d88\u606f"', '"\u5220\u9664\u6b64\u6d88\u606f"',
        '"\u7ba1\u7406\u5458\u64cd\u4f5c"', '"\u6e05\u7a7a\u6240\u6709\u6d88\u606f"',
        '"\u5220\u9664N\u5929\u524d\u7684\u6d88\u606f..."',
        '"\u5220\u9664\u6700\u8fd1N\u5929\u7684\u6d88\u606f..."',
    )):
        raise AssertionError(
            "message context actions must project from the active Windows catalog"
        )
    require(message_delegate, (
        "WindowsAttachmentPresentation::unavailableText(",
        "WindowsMessagePresentation::timestampWithDelivery(",
        "WindowsMessagePresentation::transferStatus(",
        "WindowsMessagePresentation::recalledText(",
        "MessageModel::ClearReasonRole",
        "WindowsLocaleViewModel::changed",
        "view->viewport()->update()",
    ), "Client/MessageDelegate.cpp")
    if "文件已过期或被清除" in message_delegate:
        raise AssertionError(
            "MessageDelegate must project unavailable attachment copy from the locale catalog"
        )
    if any(copy in message_delegate for copy in (
        'QStringLiteral("昨天 ', 'QStringLiteral(" · 发送中")',
        'QStringLiteral(" · 发送失败")', 'QStringLiteral(" · 已读")',
        'QStringLiteral(" · 已发送")', 'QString("  下载中',
        'QString("  已暂停', 'QString("  上传中',
        'QString("  上传已暂停', 'QStringLiteral("  点击下载")',
        'QStringLiteral("加载中...")', 'QString("%1 撤回了一条消息")',
    )):
        raise AssertionError(
            "MessageDelegate must not embed timeline or transfer presentation copy"
        )
    require(attachment_presentation, (
        "!safeServerReason.trimmed().isEmpty()",
        "return safeServerReason",
        "roomFileClearedUnavailable",
    ), "Client/WindowsAttachmentPresentation.cpp")
    require(message_presentation, (
        "mainMessageYesterday.arg(time)",
        "mainMessageSendingSuffix",
        "mainMessageDownloadingSuffix.arg(percent)",
        "mainMessageRecalled.arg(senderName)",
    ), "Client/WindowsMessagePresentation.cpp")
    if "markFilesCleared(ids, QStringLiteral" in window:
        raise AssertionError(
            "local file-clearing events must not persist locale-specific copy"
        )
    composer_surface = window[
        window.index("// 工具栏"):
        window.index("// --- 右侧：用户列表 ---")
    ]
    if any(text in composer_surface for text in (
        'new QPushButton("表情")', 'new QPushButton("文件")',
        'new QPushButton("发送")', "输入消息...", 'addAction("插入换行"',
    )):
        raise AssertionError("ChatWindow composer must not embed localized copy")
    composer_projection = window[
        window.index("void ChatWindow::refreshComposerText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in composer_projection for marker in (
        "m_inputEdit->clear()", "m_inputEdit->setPlainText(",
        "m_inputEdit->moveCursor(",
    )):
        raise AssertionError("locale projection must not mutate composer draft state")
    navigation_setup = window[
        window.index("// --- 左侧：房间列表 ---"):
        window.index("// --- 中间：消息区域 ---")
    ]
    if any(text in navigation_setup for text in (
        'new QPushButton("房间")', 'new QPushButton("好友")',
        'new QPushButton("创建")', 'new QPushButton("搜索好友")',
        'new QPushButton("好友申请")',
    )):
        raise AssertionError("ChatWindow navigation must not embed localized controls")
    if 'label += " [在线]"' in window or '+ " [在线]"' in window:
        raise AssertionError("friend online state must not be stored in display text")
    navigation_projection = window[
        window.index("void ChatWindow::refreshNavigationText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in navigation_projection for marker in (
        "m_roomList->clear()", "m_friendList->clear()",
        "m_roomList->setCurrentRow(", "m_friendList->setCurrentRow(",
    )):
        raise AssertionError("locale projection must not rebuild navigation identity")
    conversation_projection = window[
        window.index("void ChatWindow::refreshConversationShellText()"):
        window.index("void ChatWindow::showAboutDialog()")
    ]
    if any(marker in conversation_projection for marker in (
        "m_messageView->setModel(", "m_userList->clear()",
        "m_currentRoomId =", "m_currentFriendUsername.clear()",
    )):
        raise AssertionError("locale projection must not mutate conversation identity")
    conversation_setup = window[
        window.index("// --- 中间：消息区域 ---"):
        window.index("// 组装")
    ]
    if any(text in conversation_setup for text in (
        'new QLabel("请选择一个窗口")',
        'setToolTip("房间设置")', 'new QLabel("聊天室成员")',
    )):
        raise AssertionError("conversation shell must not embed localized copy")
    if 'new QLabel(isOnline ? "在线" : "离线")' in window:
        raise AssertionError("member status presentation must come from the catalog")
    create_room_surface = window[
        window.index("void ChatWindow::onCreateRoom()"):
        window.index("void ChatWindow::onSearchRoom()")
    ]
    if "QInputDialog::getText" in create_room_surface:
        raise AssertionError("create-room dialog must support live locale projection")
    if any(marker in create_room_surface for marker in (
        "dialog.setTextValue(", "dialog.textValue().clear()",
    )):
        raise AssertionError("locale projection must not replace the room-name draft")
    room_search_surface = window[
        window.index("void ChatWindow::onSearchRoom()"):
        window.index("void ChatWindow::onRoomCreated(")
    ]
    if "new QPushButton" in room_search_surface or "new QLabel" in room_search_surface:
        raise AssertionError("ChatWindow must not own room-search presentation")
    require(room_search, (
        "copy.mainRoomSearchTitle",
        "copy.mainRoomSearchResultAccessible",
        "emit searchRequested(keyword)",
        "emit joinRequested(roomId)",
        "emit roomAvatarRequested(result.roomId)",
        "m_searchInput->text().trimmed()",
        "m_resultList->setItemWidget(item, itemWidget)",
    ), "Client/RoomSearchDialog.cpp")
    if "NetworkManager" in room_search:
        raise AssertionError("room-search presentation must not own transport")
    friend_search_surface = window[
        window.index("void ChatWindow::onAddFriend()"):
        window.index("void ChatWindow::onShowFriendRequests()")
    ]
    if "new QPushButton" in friend_search_surface or "new QLabel" in friend_search_surface:
        raise AssertionError("ChatWindow must not own friend-search presentation")
    require(friend_search, (
        "copy.mainFriendSearchTitle",
        "copy.mainFriendSearchResultOnlineAccessible",
        "emit searchRequested(keyword)",
        "emit friendRequestRequested(username)",
        "emit avatarRequested(result.username)",
        "result.currentAccount",
        "MaxResults",
    ), "Client/FriendSearchDialog.cpp")
    if "NetworkManager" in friend_search:
        raise AssertionError("friend-search presentation must not own transport")
    friend_requests_surface = window[
        window.index("void ChatWindow::onFriendPendingReceived("):
        window.index("void ChatWindow::onFriendChatMessage(")
    ]
    if "new QPushButton" in friend_requests_surface or "new QLabel" in friend_requests_surface:
        raise AssertionError("ChatWindow must not own friend-request presentation")
    require(friend_requests, (
        "MaxRequests",
        "QSet<int> seenIds",
        "QSet<QString> seenUsernames",
        "m_pendingOperation != Operation::None",
        "m_pendingOperation != operation",
        "RowState::Accepted",
        "RowState::Rejected",
        "emit acceptRequested(requestId, username)",
        "emit rejectRequested(requestId)",
        "emit avatarRequested(request.username)",
        "copy.mainFriendRequestsTitle",
        "copy.mainFriendRequestsRowAccessible",
    ), "Client/FriendRequestsDialog.cpp")
    if "NetworkManager" in friend_requests:
        raise AssertionError("friend-request presentation must not own transport")
    friend_lifecycle_surface = window[
        window.index("void ChatWindow::onFriendContextMenu("):
        window.index("void ChatWindow::onFriendListReceived(")
    ]
    for embedded_copy in (
        "查看信息", "删除好友", "好友请求已发送",
        "添加好友", "收到好友请求", "已接受你的好友请求",
        "已将你从好友列表移除",
    ):
        if embedded_copy in friend_lifecycle_surface:
            raise AssertionError(
                "friend lifecycle must not embed localized presentation copy"
            )
    leave_room_surface = window[
        window.index("void ChatWindow::leaveRoom("):
        window.index("// ==================== 用户列表辅助方法")
    ]
    if "退出聊天室" in leave_room_surface:
        raise AssertionError("leave-room flow must use catalog presentation copy")
    room_management_surface = window[
        window.index("void ChatWindow::onDeleteRoomResponse("):
        window.index("// ==================== 修改昵称")
    ]
    for embedded_copy in (
        "删除成功", "聊天室已删除", "修改成功",
        "聊天室名称修改成功", "踢人失败", "被踢出聊天室",
    ):
        if embedded_copy in room_management_surface:
            raise AssertionError(
                "room-management result flow must use catalog presentation copy"
            )
    identity_change_surface = window[
        window.index("void ChatWindow::onChangeNicknameResponse("):
        window.index("// ==================== 房间设置对话框")
    ]
    if any(copy in identity_change_surface for copy in (
        "昵称已修改为", "修改昵称失败", "用户ID已修改为",
        "修改用户ID失败", "Qt聊天室 - %1",
        "本地消息缓存迁移失败",
    )):
        raise AssertionError(
            "identity-change result flow must use catalog presentation copy"
        )
    if "refreshWindowChrome();" not in identity_change_surface:
        raise AssertionError(
            "nickname changes must reproject the existing localized window chrome"
        )
    avatar_change_surface = window[
        window.index("void ChatWindow::onChangeAvatar()"):
        window.index("void ChatWindow::onAvatarGetResponse(")
    ]
    require(avatar_change_surface, (
        'cropped.save(&buf, "PNG")',
        "pngData.size() > 256 * 1024",
        "QString::fromLatin1(pngData.toBase64())",
        "Protocol::MsgType::AVATAR_UPLOAD_REQ",
        "requestAvatar(m_username, true)",
    ), "Client/ChatWindow.cpp avatar change surface")
    if any(copy in avatar_change_surface for copy in (
        "选择头像图片", "图片文件", "无法加载图片",
        "头像数据过大", "头像上传成功", "头像上传失败",
    )):
        raise AssertionError("avatar-change flow must use catalog presentation copy")
    connection_surface = window[
        window.index("// ==================== 连接状态"):
        window.index("// ==================== 窗口事件")
    ]
    if "m_statusLabel->setText" in connection_surface or any(
        text in connection_surface for text in ("已连接", "已断开", "重连中")
    ):
        raise AssertionError(
            "connection lifecycle must not overwrite activity or own localized copy"
        )
    close_surface = window[
        window.index("void ChatWindow::closeEvent(QCloseEvent *event)"):
        window.index("void ChatWindow::moveEvent(QMoveEvent *event)")
    ]
    if 'showNotification("Qt聊天室"' in close_surface:
        raise AssertionError("minimize-to-tray notification must use the locale catalog")
    require(connection_status, (
        "State::Disconnected",
        "State::Connected",
        "State::Reconnecting",
        "std::max(1, attempt)",
        "emit changed()",
    ), "Client/WindowsConnectionStatusViewModel.cpp")
    menu_surface = window[
        window.index("void ChatWindow::setupMenuBar()"):
        window.index("// ==================== 信号连接")
    ]
    if any(text in menu_surface for text in (
        "文件(&F)", "视图(&V)", "设置(&S)", "帮助(&H)",
        "登录设备(&D)...", "检查更新(&U)...",
    )):
        raise AssertionError("ChatWindow menu surface must not embed localized copy")
    require(tray, (
        "WindowsLocaleViewModel::changed",
        "WindowsLocaleCatalog::messages(locale)",
        "copy.trayApplicationName",
        "copy.trayShowMainWindow",
        "copy.trayQuit",
        "m_showAction->setText",
        "m_quitAction->setText",
    ), "Client/TrayManager.cpp")
    if any(text in tray for text in ("Qt聊天室", "显示主窗口", "退出")):
        raise AssertionError("TrayManager must not own localized presentation copy")
    require(profile, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_localeSelector->addItem",
        "m_localeViewModel->select",
        "copy.profileLowBandwidth",
        "copy.profileChangePassword",
        "m_bandwidthViewModel->enabled()",
        "WindowsBandwidthViewModel::select",
        "m_bandwidthViewModel->saveFailed()",
    ), "Client/ProfileDialog.cpp")
    require(room_settings, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_passwordEdit->setEchoMode(QLineEdit::Password)",
        "m_developerKeyEdit->clear()",
        "m_passwordEdit->clear()",
        "AvatarCropDialog dlg(img, this, m_localeViewModel)",
    ), "Client/RoomSettingsDialog.cpp")
    require(room_files, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "updateLocalizedRows()",
        'statusItem->setData(Qt::UserRole, cleared)',
        "check->property(\"fileId\").toInt()",
    ), "Client/RoomFileManagerDialog.cpp")
    require(room_password, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "m_passwordEdit->setEchoMode(QLineEdit::Password)",
        "if (password.isEmpty())",
        "m_passwordEdit->clear()",
        "emit joinRequested(m_roomId, password)",
    ), "Client/RoomPasswordPromptDialog.cpp")
    require(device_dialog, (
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "DeviceManagementViewModel::Failure::InvalidDirectory",
        "item->setData(Qt::UserRole, device.deviceId)",
        "description->setTextFormat(Qt::PlainText)",
        "confirmation.setDefaultButton(cancel)",
        "confirmation.clickedButton() == confirm",
        "m_viewModel->revoke(deviceId)",
    ), "Client/DeviceManagementDialog.cpp")
    require(notification_policy, (
        "Kind::Mention : Kind::GenericMessage",
        "decision.conversationId = message.conversationId",
    ), "Client/WindowsMessageNotificationPolicy.cpp")
    for forbidden in ("有人提到了你", "新消息", "打开聊天软件查看消息"):
        if forbidden in notification_policy:
            raise AssertionError("notification policy must retain semantics, not catalog copy")
    require(notification_presenter, (
        "m_localeViewModel->locale()",
        "copy.notificationMentionedYou",
        "copy.notificationNewMessage",
        "copy.notificationOpenApp",
    ), "Client/WindowsMessageNotificationPresenter.cpp")
    require(bandwidth_policy, (
        "WindowsBandwidthPolicy::shouldAutoRequestAvatar",
        "!lowBandwidthEnabled && !cached",
        "if (!explicitRequest",
        "m_dispatch(accountId)",
    ), "Client/WindowsBandwidthPolicy.cpp")
    for source, text in (
        ("Client/WindowsV2MessagingController.cpp", messaging_controller),
        ("Client/V2WindowsMessagingPanel.cpp", panel),
    ):
        if "lowBandwidth" in text:
            raise AssertionError(
                f"{source} must not weaken messaging for low-bandwidth mode"
            )
    require(controller, (
        "ReadyForAuthentication",
        "authenticationRejected",
        "DeviceManagementViewModel::applyDirectory",
        "DeviceManagementViewModel::applyRevoked",
        "DeviceManagementViewModel::applyProtocolError",
        "enableMessageForwarding",
        "enableMessageSearch",
        "enableAccountBlocking",
        "WindowsAccountBlockController",
        "accountBlockViewModel",
    ), "Client/WindowsDeviceManagementController.cpp")
    require(block_controller, (
        "m_directoryViewModel->applyFailure(event.retryable)",
        "m_viewModel->applyFailure(operationId, event.retryable)",
    ), "Client/WindowsAccountBlockController.cpp")
    if any(text in block_controller for text in (
        "屏蔽目录暂不可用", "无法读取屏蔽目录",
    )):
        raise AssertionError(
            "WindowsAccountBlockController must not manufacture localized server detail"
        )
    require(session_protocol, (
        "CLIENT_CAPABILITY_MESSAGE_MENTIONS",
        "CLIENT_CAPABILITY_MESSAGE_SEARCH",
        "CLIENT_CAPABILITY_ACCOUNT_BLOCKING",
    ), "Client/V2WindowsSessionProtocolClient.cpp")
    require(messaging_controller, (
        "participant.accountId) == m_accountId",
        "m_participantProtocol->abandon(command.requestId)",
        "m_participantViewModel->refresh()",
        "m_service->stageText(",
        "m_service->saveDraft(",
        "configureForwarding(",
        "V2WindowsMessageSearchProtocolClient",
        "requestSearch(",
    ), "Client/WindowsV2MessagingController.cpp")
    require(panel, (
        "m_viewModel->sendText(",
        "m_draftSaveTimer->setInterval(400)",
        "m_viewModel->persistDraft(",
        "key->modifiers() == Qt::ControlModifier",
        "key->key() == Qt::Key_Escape",
        "V2LocalMessageRepository::MaxTextBytes",
        "copy.composerBudgetAccessible",
        "copy.copyMessageAccessible",
        "QGuiApplication::clipboard()",
        "m_mentionsEnabled && !m_conversationId.isEmpty()",
        "V2WindowsMentionComposer::serialize",
        'setProperty("mentionTargetAccountIds"',
        "copy.searchInputAccessible",
        "V2WindowsMessageSearchViewModel::loadMore",
        "scrollToItem(item, QAbstractItemView::PositionAtCenter)",
    ), "Client/V2WindowsMessagingPanel.cpp")
    require(conversation_dialog, (
        "V2WindowsAccountBlockDialog",
        "m_selectedConversationDirect",
        "m_accountBlock->setEnabled",
        "m_localeViewModel->select(locale)",
        "m_messagingPanel->setLocale(m_locale)",
    ), "Client/V2WindowsConversationDialog.cpp")
    require(block_directory_dialog, (
        "requestUnblock",
        "WindowsLocaleCatalog::messages(m_locale)",
        "WindowsLocaleViewModel::changed",
        "failureDetail().isEmpty()",
        "confirmation.setDefaultButton(cancel)",
    ), "Client/V2WindowsAccountBlockDirectoryDialog.cpp")
    for source in (
        "Client/WindowsDeviceManagementController.cpp",
        "Client/DeviceManagementApplicationService.cpp",
        "Client/WindowsDeviceIdentityRepository.cpp",
    ):
        if "QSettings" in (ROOT / source).read_text(encoding="utf-8"):
            raise AssertionError(f"{source} must not persist V2 security state in QSettings")
    if "WindowsDeviceManagementController" in qmake or "protobuf" in qmake.lower():
        raise AssertionError("qmake rollback must not activate the canonical CMake V2 stack")

    print("Windows V2 product composition policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
