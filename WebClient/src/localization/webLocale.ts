export const SUPPORTED_WEB_LOCALES = ["zh-CN", "en-US"] as const;
export type WebLocale = typeof SUPPORTED_WEB_LOCALES[number];

export interface WebLocaleStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const STORAGE_KEY = "chat.web.locale";

const loginCatalog = {
  "zh-CN": {
    language: "界面语言",
    loginSubtitle: "登录到聊天室",
    registerSubtitle: "注册新账号",
    userId: "用户ID (唯一标识)",
    userIdPlaceholder: "输入唯一用户ID",
    displayName: "昵称",
    displayNamePlaceholder: "输入显示昵称",
    password: "密码",
    passwordPlaceholder: "输入密码",
    confirmPassword: "确认密码",
    confirmPasswordPlaceholder: "再次输入密码",
    connecting: "连接中...",
    login: "登录",
    register: "注册",
    switchToLogin: "已有账号？去登录",
    switchToRegister: "没有账号？注册",
    v2Preview: "V2 工程预览",
    lightTheme: "切换到浅色主题",
    darkTheme: "切换到深色主题",
    loginRequired: "请输入用户ID和密码",
    registerRequired: "请填写所有字段",
    passwordMismatch: "两次密码不一致",
    connectionFailed: "无法连接到服务器",
    offline: "网络已断开，请在恢复连接后重试",
    onlineAgain: "网络已恢复，可以重新登录",
    loginFailed: "登录失败",
    registerFailed: "注册失败",
  },
  "en-US": {
    language: "Interface language",
    loginSubtitle: "Sign in to ChatRoom",
    registerSubtitle: "Create an account",
    userId: "User ID (unique)",
    userIdPlaceholder: "Enter your unique user ID",
    displayName: "Display name",
    displayNamePlaceholder: "Enter your display name",
    password: "Password",
    passwordPlaceholder: "Enter your password",
    confirmPassword: "Confirm password",
    confirmPasswordPlaceholder: "Enter your password again",
    connecting: "Connecting...",
    login: "Sign in",
    register: "Register",
    switchToLogin: "Already registered? Sign in",
    switchToRegister: "Need an account? Register",
    v2Preview: "V2 engineering preview",
    lightTheme: "Switch to light theme",
    darkTheme: "Switch to dark theme",
    loginRequired: "Enter your user ID and password",
    registerRequired: "Complete all required fields",
    passwordMismatch: "The passwords do not match",
    connectionFailed: "Unable to connect to the server",
    offline: "You are offline. Reconnect and try again",
    onlineAgain: "You are online again and can retry sign-in",
    loginFailed: "Sign-in failed",
    registerFailed: "Registration failed",
  },
} as const;

const shellCatalog = {
  "zh-CN": {
    language: "界面语言",
    chinese: "简体中文",
    english: "English",
    offlineBanner: "网络已断开，可继续查看已缓存消息，恢复后将自动连接",
    openProfile: "打开个人资料",
    userAvatar: "用户头像",
    theme: "切换主题",
    lightTheme: "切换到浅色主题",
    darkTheme: "切换到深色主题",
    conversationTypes: "会话类型",
    friends: "好友",
    rooms: "房间",
    openConversations: "打开会话列表",
    conversationList: "会话列表",
    openMembers: "打开成员列表",
    memberList: "成员列表",
    openRoomSettings: "打开房间设置",
    roomSettings: "房间设置",
    directMessage: "私聊",
    offlineEmpty: "网络已断开，将在恢复后自动连接",
    reconnecting: "正在重新连接...",
    selectFriend: "选择一个窗口开始聊天",
    selectRoom: "选择一个房间开始聊天",
    closeMembers: "关闭成员列表",
    close: "关闭",
    connectionLost: "连接已断开",
    signInAgain: "重新登录",
  },
  "en-US": {
    language: "Interface language",
    chinese: "简体中文",
    english: "English",
    offlineBanner: "You are offline. Cached messages remain available and reconnection is automatic.",
    openProfile: "Open profile",
    userAvatar: "User avatar",
    theme: "Switch theme",
    lightTheme: "Switch to light theme",
    darkTheme: "Switch to dark theme",
    conversationTypes: "Conversation types",
    friends: "Friends",
    rooms: "Rooms",
    openConversations: "Open conversation list",
    conversationList: "Conversation list",
    openMembers: "Open member list",
    memberList: "Members",
    openRoomSettings: "Open room settings",
    roomSettings: "Room settings",
    directMessage: "Direct message",
    offlineEmpty: "You are offline. Reconnection will start automatically.",
    reconnecting: "Reconnecting...",
    selectFriend: "Select a conversation to start chatting",
    selectRoom: "Select a room to start chatting",
    closeMembers: "Close member list",
    close: "Close",
    connectionLost: "Connection closed",
    signInAgain: "Sign in again",
  },
} as const;

const profileCatalog = {
  "zh-CN": {
    title: "个人资料",
    language: "界面语言",
    chinese: "简体中文",
    english: "English",
    changeAvatar: "更换头像",
    currentAvatar: "当前头像",
    selectAvatar: "选择新头像",
    displayName: "昵称",
    edit: "编辑",
    save: "保存",
    userId: "用户ID",
    userIdHint: "6-20位字母/数字/下划线，每月仅可修改一次",
    changePassword: "修改密码",
    currentPassword: "当前密码",
    newPassword: "新密码",
    confirmPassword: "确认新密码",
    lowBandwidth: "省流量模式",
    lowBandwidthDescription: "开启后不再自动请求联系人和消息列表头像；消息、离线同步和您主动打开的资料仍正常工作。",
    browserDataSaver: "已根据浏览器的节省流量设置自动开启",
    sessionOnly: "浏览器禁止保存设置，本次会话内有效",
    signOut: "退出登录",
    close: "关闭",
    avatarTooLarge: "头像太大，请选择更小的图片",
    invalidUserId: "用户ID必须为6-20位，只能包含字母、数字和下划线",
    userIdChanged: "用户ID修改成功",
    changeFailed: "修改失败",
    requiredFields: "请填写所有字段",
    passwordMismatch: "两次新密码不一致",
  },
  "en-US": {
    title: "Profile",
    language: "Interface language",
    chinese: "简体中文",
    english: "English",
    changeAvatar: "Change avatar",
    currentAvatar: "Current avatar",
    selectAvatar: "Choose a new avatar",
    displayName: "Display name",
    edit: "Edit",
    save: "Save",
    userId: "User ID",
    userIdHint: "6–20 letters, numbers, or underscores; editable once per month",
    changePassword: "Change password",
    currentPassword: "Current password",
    newPassword: "New password",
    confirmPassword: "Confirm new password",
    lowBandwidth: "Low-bandwidth mode",
    lowBandwidthDescription: "Stops automatic avatar requests in contacts and message lists. Messages, offline sync, and profiles you open still work.",
    browserDataSaver: "Enabled automatically from your browser's data-saver setting",
    sessionOnly: "Your browser blocked preference storage; this session only",
    signOut: "Sign out",
    close: "Close",
    avatarTooLarge: "The avatar is too large. Choose a smaller image.",
    invalidUserId: "User ID must be 6–20 letters, numbers, or underscores",
    userIdChanged: "User ID updated",
    changeFailed: "Update failed",
    requiredFields: "Complete all required fields",
    passwordMismatch: "The new passwords do not match",
  },
} as const;

const friendListCatalog = {
  "zh-CN": {
    title: "好友列表", searchFriends: "搜索好友", requests: "好友申请",
    pendingRequests: "好友申请（有待处理申请）", refresh: "刷新好友列表",
    online: "在线", offline: "离线", empty: "暂无好友，点击搜索按钮查找好友",
    avatarPrefix: "", avatarSuffix: " 的头像", searchLabel: "用户 ID 或昵称",
    searchPlaceholder: "输入用户ID或昵称搜索", searching: "搜索中…", search: "搜索",
    searchHint: "输入关键词后点击搜索", noResults: "未找到匹配的用户",
    userId: "ID", added: "已添加", sendRequest: "发送申请",
    pendingTitle: "待处理的好友申请", noPending: "暂无待处理的好友申请",
    accept: "接受", reject: "拒绝", close: "关闭", menu: "好友操作",
    viewInfo: "查看信息", remove: "删除好友", removeConfirmPrefix: "确定要删除好友 ",
    removeConfirmSuffix: " 吗？",
  },
  "en-US": {
    title: "Friend list", searchFriends: "Find friends", requests: "Friend requests",
    pendingRequests: "Friend requests (pending)", refresh: "Refresh friend list",
    online: "Online", offline: "Offline", empty: "No friends yet. Use Find friends to get started.",
    avatarPrefix: "Avatar for ", avatarSuffix: "", searchLabel: "User ID or display name",
    searchPlaceholder: "Search by user ID or display name", searching: "Searching…", search: "Search",
    searchHint: "Enter a keyword and select Search", noResults: "No matching users",
    userId: "ID", added: "Added", sendRequest: "Send request",
    pendingTitle: "Pending friend requests", noPending: "No pending friend requests",
    accept: "Accept", reject: "Decline", close: "Close", menu: "Friend actions",
    viewInfo: "View profile", remove: "Remove friend", removeConfirmPrefix: "Remove ",
    removeConfirmSuffix: " from your friends?",
  },
} as const;

const roomListCatalog = {
  "zh-CN": {
    title: "房间列表", searchRooms: "搜索房间", createRoom: "创建房间",
    refresh: "刷新房间列表", empty: "暂无房间，使用创建或搜索按钮开始",
    avatarPrefix: "", avatarSuffix: " 的房间头像", searchLabel: "房间名称或 ID",
    searchPlaceholder: "输入房间名称或ID搜索", searching: "搜索中…", search: "搜索",
    searchHint: "输入关键词后点击搜索", noResults: "未找到匹配的房间",
    userId: "ID", memberSuffix: " 人", joined: "已加入", join: "加入", close: "关闭",
    roomName: "房间名称", roomNamePlaceholder: "输入房间名称",
    optionalPassword: "密码（可选）", passwordPlaceholder: "留空则无密码",
    cancel: "取消", create: "创建", menu: "房间操作", settings: "房间设置",
    files: "文件管理",
  },
  "en-US": {
    title: "Room list", searchRooms: "Find rooms", createRoom: "Create room",
    refresh: "Refresh room list", empty: "No rooms yet. Use Create room or Find rooms to get started.",
    avatarPrefix: "Room avatar for ", avatarSuffix: "", searchLabel: "Room name or ID",
    searchPlaceholder: "Search by room name or ID", searching: "Searching…", search: "Search",
    searchHint: "Enter a keyword and select Search", noResults: "No matching rooms",
    userId: "ID", memberSuffix: " members", joined: "Joined", join: "Join", close: "Close",
    roomName: "Room name", roomNamePlaceholder: "Enter a room name",
    optionalPassword: "Password (optional)", passwordPlaceholder: "Leave blank for no password",
    cancel: "Cancel", create: "Create", menu: "Room actions", settings: "Room settings",
    files: "File management",
  },
} as const;

const memberListCatalog = {
  "zh-CN": {
    onlineCountPrefix: "在线 ", onlineCountSuffix: "", onlineMembers: "在线成员",
    offlineCountPrefix: "离线 ", offlineCountSuffix: "", offlineMembers: "离线成员",
    online: "在线", offline: "离线", admin: "管理员",
    avatarPrefix: "", avatarSuffix: " 的头像", separator: "，",
  },
  "en-US": {
    onlineCountPrefix: "", onlineCountSuffix: " online", onlineMembers: "Online members",
    offlineCountPrefix: "", offlineCountSuffix: " offline", offlineMembers: "Offline members",
    online: "online", offline: "offline", admin: "administrator",
    avatarPrefix: "Avatar for ", avatarSuffix: "", separator: ", ",
  },
} as const;

const composerCatalog = {
  "zh-CN": {
    toolbar: "消息工具", emoji: "表情", selectEmoji: "选择表情", sendFile: "发送文件",
    selectFile: "选择要发送的文件", reselectFileInput: "重新选择待发送文件",
    uploadStatus: "文件上传状态", uploadProgressSuffix: " 上传进度", syncing: "☁同步中",
    pauseUploadPrefix: "暂停上传 ", resumeUploadPrefix: "继续上传 ", cancelUploadPrefix: "取消上传 ",
    pause: "暂停", resume: "继续", cancel: "取消", recoveryStatus: "待恢复的文件发送",
    needsSource: "需要重新选择原文件", sendFailed: "发送失败", reselectPrefix: "重新选择 ",
    retrySendPrefix: "重试发送 ", cancelSendPrefix: "取消发送 ", reselect: "重新选择",
    retry: "重试", messagePlaceholder: "输入消息...", messageContent: "消息内容",
    sendMessage: "发送消息", send: "发送", messageBytes: "消息字节数", bytes: "字节",
    overLimitPrefix: "超过上限 ", maximumPrefix: "（最多 ", maximumSuffix: " 字节）",
    friendFileTooLarge: "私聊单文件不能超过 100MB", roomFileTooLargePrefix: "文件大小超过房间上限（",
    roomFileTooLargeSuffix: "MB）",
  },
  "en-US": {
    toolbar: "Message tools", emoji: "Emoji", selectEmoji: "Choose emoji", sendFile: "Send file",
    selectFile: "Choose a file to send", reselectFileInput: "Choose the original file again",
    uploadStatus: "File upload status", uploadProgressSuffix: " upload progress", syncing: "☁Syncing",
    pauseUploadPrefix: "Pause upload: ", resumeUploadPrefix: "Resume upload: ", cancelUploadPrefix: "Cancel upload: ",
    pause: "Pause", resume: "Resume", cancel: "Cancel", recoveryStatus: "Recoverable file sends",
    needsSource: "Original file required", sendFailed: "Send failed", reselectPrefix: "Choose again: ",
    retrySendPrefix: "Retry send: ", cancelSendPrefix: "Cancel send: ", reselect: "Choose again",
    retry: "Retry", messagePlaceholder: "Type a message...", messageContent: "Message content",
    sendMessage: "Send message", send: "Send", messageBytes: "Message byte count", bytes: "bytes",
    overLimitPrefix: "Over limit by ", maximumPrefix: " (maximum ", maximumSuffix: " bytes)",
    friendFileTooLarge: "Direct-message files cannot exceed 100 MB", roomFileTooLargePrefix: "File exceeds the room limit (",
    roomFileTooLargeSuffix: " MB)",
  },
} as const;

const emojiPickerCatalog = {
  "zh-CN": {
    dialog: "选择要发送的表情",
    grid: "表情",
    sendPrefix: "发送表情 ",
  },
  "en-US": {
    dialog: "Choose an emoji to send",
    grid: "Emoji",
    sendPrefix: "Send emoji ",
  },
} as const;

const messageTimelineCatalog = {
  "zh-CN": {
    timeline: "聊天消息", loading: "加载中...", recalledSuffix: " 撤回了一条消息",
    viewProfilePrefix: "查看 ", viewProfileSuffix: " 的资料", avatarPrefix: "", avatarSuffix: " 的头像",
    sending: "发送中…", failedRetryLabel: "发送失败，重试这条消息",
    failedRetry: "发送失败，点击重试", read: "已读", sent: "已发送",
    systemPrefix: "系统消息：", user: "用户", self: "我", filePrefix: "文件 ",
    separator: "，", contentSeparator: "：", newMessagesSuffix: " 条新消息",
    backToLatestSuffix: "，回到最新消息", readingHistorySuffix: "，当前仍在阅读历史消息",
    copySucceeded: "消息正文已复制", copyFailed: "无法复制消息正文，请检查浏览器权限",
  },
  "en-US": {
    timeline: "Chat messages", loading: "Loading...", recalledSuffix: " recalled a message",
    viewProfilePrefix: "View ", viewProfileSuffix: " profile", avatarPrefix: "Avatar for ", avatarSuffix: "",
    sending: "Sending…", failedRetryLabel: "Send failed; retry this message",
    failedRetry: "Send failed. Select to retry.", read: "Read", sent: "Sent",
    systemPrefix: "System message: ", user: "User", self: "Me", filePrefix: "File ",
    separator: ", ", contentSeparator: ": ", newMessagesSuffix: " new messages",
    backToLatestSuffix: "; return to latest", readingHistorySuffix: "; still reading message history",
    copySucceeded: "Message text copied", copyFailed: "Unable to copy message text. Check browser permissions.",
  },
} as const;

const messageAttachmentCatalog = {
  "zh-CN": {
    image: "图片", chatImage: "聊天图片", imageThumbnail: "聊天图片缩略图",
    video: "视频", file: "文件", thumbnailSuffix: " 缩略图",
    expiredImagePrefix: "查看已过期图片 ", previewImagePrefix: "预览图片 ",
    expiredVideoPrefix: "查看已过期视频 ", previewVideoPrefix: "预览视频 ",
    expiredFilePrefix: "查看已过期文件 ", previewFilePrefix: "预览文件 ",
    expired: "文件已过期或被清除", cannotPreview: "文件已过期或被清除，无法预览",
    cannotDownload: "文件已过期或被清除，无法下载",
    cannotForward: "文件已过期或被清除，Web 端不支持转发",
  },
  "en-US": {
    image: "Image", chatImage: "Chat image", imageThumbnail: "Chat image thumbnail",
    video: "Video", file: "File", thumbnailSuffix: " thumbnail",
    expiredImagePrefix: "View expired image: ", previewImagePrefix: "Preview image: ",
    expiredVideoPrefix: "View expired video: ", previewVideoPrefix: "Preview video: ",
    expiredFilePrefix: "View expired file: ", previewFilePrefix: "Preview file: ",
    expired: "File expired or was removed", cannotPreview: "This file expired or was removed and cannot be previewed.",
    cannotDownload: "This file expired or was removed and cannot be downloaded.",
    cannotForward: "This file expired or was removed and cannot be forwarded on Web.",
  },
} as const;

const messageActionCatalog = {
  "zh-CN": {
    menu: "消息操作", copyText: "复制文本", previewFile: "预览文件", downloadFile: "下载文件",
    forward: "转发到其他会话", recall: "撤回", deleteMessage: "删除此消息",
    clearAll: "清空所有消息", deleteOlder: "删除N天前的消息", deleteRecent: "删除最近N天的消息",
    selectForwardTarget: "请至少选择一个转发目标", forwardSubmittedPrefix: "已提交转发到 ",
    forwardSubmittedSuffix: " 个会话", forwardFailed: "转发失败", confirmDelete: "确定删除此消息？",
    confirmClear: "确定要清空所有聊天记录吗？\n此操作不可恢复！",
    deleteOlderPrompt: "删除多少天前的消息：", deleteRecentPrompt: "删除最近几天的消息：",
    invalidDays: "请输入有效的天数",
  },
  "en-US": {
    menu: "Message actions", copyText: "Copy text", previewFile: "Preview file", downloadFile: "Download file",
    forward: "Forward to other conversations", recall: "Recall", deleteMessage: "Delete this message",
    clearAll: "Clear all messages", deleteOlder: "Delete messages older than N days",
    deleteRecent: "Delete messages from the last N days", selectForwardTarget: "Select at least one forwarding target.",
    forwardSubmittedPrefix: "Forward submitted to ", forwardSubmittedSuffix: " conversation(s).",
    forwardFailed: "Forwarding failed", confirmDelete: "Delete this message?",
    confirmClear: "Clear all chat history?\nThis action cannot be undone!",
    deleteOlderPrompt: "Delete messages older than how many days?",
    deleteRecentPrompt: "Delete messages from the last how many days?", invalidDays: "Enter a valid number of days.",
  },
} as const;

const filePreviewCatalog = {
  "zh-CN": {
    zoomOut: "缩小图片", resetZoomPrefix: "重置图片缩放，当前 ", resetZoomSuffix: "%", zoomIn: "放大图片",
    downloadTitle: "下载", downloadPrefix: "下载 ", closeTitle: "关闭", close: "关闭文件预览",
    loading: "加载中...", imagePreviewSuffix: " 预览", videoPlayerSuffix: " 视频播放器",
    audioPlayerSuffix: " 音频播放器", pdfPreviewSuffix: " PDF 预览", textPreviewSuffix: " 文本预览",
    unsupportedHint: "该文件类型不支持在线预览", downloadFile: "下载文件", unknownFile: "未知文件",
    cannotDownload: "文件已过期或被清除，无法下载", cannotPreview: "文件已过期或被清除，无法预览",
  },
  "en-US": {
    zoomOut: "Zoom out", resetZoomPrefix: "Reset image zoom; currently ", resetZoomSuffix: "%", zoomIn: "Zoom in",
    downloadTitle: "Download", downloadPrefix: "Download ", closeTitle: "Close", close: "Close file preview",
    loading: "Loading...", imagePreviewSuffix: " preview", videoPlayerSuffix: " video player",
    audioPlayerSuffix: " audio player", pdfPreviewSuffix: " PDF preview", textPreviewSuffix: " text preview",
    unsupportedHint: "This file type cannot be previewed online", downloadFile: "Download file", unknownFile: "Unknown file",
    cannotDownload: "This file expired or was removed and cannot be downloaded.",
    cannotPreview: "This file expired or was removed and cannot be previewed.",
  },
} as const;

const forwardDialogCatalog = {
  "zh-CN": {
    title: "转发到其他会话", targetType: "转发目标类型", friends: "好友", rooms: "房间",
    search: "搜索转发目标", searchFriends: "搜索好友用户名或昵称", searchRooms: "搜索房间名或房间ID",
    selectAll: "全选", selectedPrefix: "已选 ", selectedSuffix: " 项", online: "在线", offline: "离线",
    noFriends: "暂无可选好友", noRooms: "暂无可选房间", roomIdPrefix: "ID: ", cancel: "取消",
    submitting: "转发中...", confirm: "确认转发",
  },
  "en-US": {
    title: "Forward to other conversations", targetType: "Forwarding target type", friends: "Friends", rooms: "Rooms",
    search: "Search forwarding targets", searchFriends: "Search friend username or display name",
    searchRooms: "Search room name or room ID", selectAll: "Select all", selectedPrefix: "Selected: ",
    selectedSuffix: "", online: "Online", offline: "Offline", noFriends: "No friends available",
    noRooms: "No rooms available", roomIdPrefix: "ID: ", cancel: "Cancel", submitting: "Forwarding...",
    confirm: "Confirm forwarding",
  },
} as const;

const downloadPanelCatalog = {
  "zh-CN": {
    title: "下载管理", expand: "展开下载管理", collapse: "收起下载管理", taskSuffix: " 下载任务",
    progressSuffix: " 下载进度", pausePrefix: "暂停下载 ", resumePrefix: "继续下载 ", cancelPrefix: "取消下载 ",
    pause: "暂停", resume: "继续", cancel: "取消", paused: "已暂停", downloading: "下载中",
  },
  "en-US": {
    title: "Downloads", expand: "Expand downloads", collapse: "Collapse downloads", taskSuffix: " download task",
    progressSuffix: " download progress", pausePrefix: "Pause download: ", resumePrefix: "Resume download: ",
    cancelPrefix: "Cancel download: ", pause: "Pause", resume: "Resume", cancel: "Cancel",
    paused: "Paused", downloading: "Downloading",
  },
} as const;

const userInfoCatalog = {
  "zh-CN": {
    title: "用户信息", previewAvatarPrefix: "预览 ", avatarSuffix: " 的头像", largeAvatarSuffix: " 的头像大图",
    name: "昵称", userId: "用户ID", status: "状态", online: "在线", offline: "离线", role: "角色",
    admin: "管理员", member: "普通成员", adminActions: "管理员操作", setAdmin: "设为管理员",
    unsetAdmin: "取消管理员", kick: "踢出聊天室", close: "关闭", kickConfirmPrefix: "确定要将 ",
    kickConfirmSuffix: " 踢出聊天室吗？", avatarPreview: "头像预览", closePreview: "关闭预览",
    largeAvatar: "用户头像大图",
  },
  "en-US": {
    title: "User information", previewAvatarPrefix: "Preview ", avatarSuffix: " avatar", largeAvatarSuffix: " large avatar",
    name: "Display name", userId: "User ID", status: "Status", online: "Online", offline: "Offline", role: "Role",
    admin: "Administrator", member: "Member", adminActions: "Administrator actions", setAdmin: "Make administrator",
    unsetAdmin: "Remove administrator", kick: "Remove from room", close: "Close", kickConfirmPrefix: "Remove ",
    kickConfirmSuffix: " from this room?", avatarPreview: "Avatar preview", closePreview: "Close preview",
    largeAvatar: "Large user avatar",
  },
} as const;

const roomPasswordCatalog = {
  "zh-CN": {
    title: "需要密码", description: "此房间需要密码才能加入", label: "房间密码",
    placeholder: "输入房间密码", cancel: "取消", join: "加入",
  },
  "en-US": {
    title: "Password required", description: "A password is required to join this room", label: "Room password",
    placeholder: "Enter room password", cancel: "Cancel", join: "Join",
  },
} as const;

const roomFileManagerCatalog = {
  "zh-CN": {
    title: "文件管理", usagePrefix: "当前文件空间: ", caption: "房间文件列表，可选择文件后批量删除",
    selectAll: "选择全部房间文件", fileName: "文件名", type: "类型", size: "大小", uploadedAt: "上传时间",
    selectFilePrefix: "选择文件 ", noFiles: "暂无文件", deleting: "删除中…", refresh: "刷新",
    deleteSelected: "删除所选", close: "关闭", image: "图片", video: "视频", file: "文件",
    deleteConfirm: "确定彻底删除选中的文件消息吗？\n删除后消息会从聊天室中完全移除，无法恢复。",
  },
  "en-US": {
    title: "File management", usagePrefix: "Current file storage: ",
    caption: "Room files; select files to delete them in a batch", selectAll: "Select all room files",
    fileName: "File name", type: "Type", size: "Size", uploadedAt: "Uploaded at", selectFilePrefix: "Select file: ",
    noFiles: "No files", deleting: "Deleting…", refresh: "Refresh", deleteSelected: "Delete selected",
    close: "Close", image: "Image", video: "Video", file: "File",
    deleteConfirm: "Permanently delete the selected file messages?\nThey will be removed from the room and cannot be restored.",
  },
} as const;

const roomSettingsCatalog = {
  "zh-CN": {
    title: "房间设置", avatarSuffix: " 的房间头像", roomName: "房间名称", roomId: "房间ID",
    administrator: "管理员", yes: "是", no: "否", maxFileSize: "单文件最大", totalFileSpace: "总文件空间",
    maxFileCount: "文件数量上限", maxMembers: "聊天室最大人数", roomAvatar: "房间头像", selectImage: "选择图片",
    selectNewAvatar: "选择新的房间头像", uploading: "上传中…", renameRoom: "重命名房间", newName: "新名称",
    modify: "修改", roomPassword: "房间密码", passwordPlaceholder: "留空则取消密码", set: "设置",
    checkStatus: "检查状态", passwordSet: "已设置密码（不可查看，可直接重设）", passwordNone: "当前未设置密码",
    manageMembers: "管理成员", selectUser: "选择用户...", adminBadge: "[管理员]", unsetAdmin: "取消管理员",
    setAdmin: "设为管理员", kick: "踢出", messageManagement: "消息管理", clearAll: "清空所有消息",
    danger: "危险操作", deleteRoom: "删除房间", limitsTitle: "限制设置（需开发者秘钥）",
    maxFileSizeGb: "单文件最大(GB)", totalFileSpaceGb: "总文件空间(GB)", developerKey: "开发者秘钥",
    developerKeyPlaceholder: "输入开发者秘钥后可保存限制", saveLimits: "保存限制", leaveRoom: "退出房间", close: "关闭",
    avatarTooLarge: "头像太大，请选择更小的图片", avatarSaved: "聊天室头像修改成功", nameSaved: "聊天室名称修改成功",
    passwordSaved: "聊天室密码设置成功", passwordRemoved: "聊天室密码已取消", limitsSaved: "房间限制修改成功",
    limitsPositive: "限制值必须大于0", totalTooSmall: "总文件空间不能小于单文件最大值",
    developerKeyRequired: "请输入开发者秘钥", cleanupPrefix: "调整限制将清理 ", cleanupFileSuffix: " 个历史文件。\n",
    cleanupAfterPrefix: "清理后预计保留 ", cleanupAfterMiddle: " 个文件，约 ", cleanupAfterSuffix: " GB。\n",
    cleanupExpiry: "被清理文件会在聊天中保留记录，但状态显示为“文件已过期或被清除”。\n\n是否继续？",
    kickPrefix: "确定踢出 ", kickSuffix: " ？", clearConfirm: "确定清空所有消息？此操作不可撤销。",
    deletePrefix: "确定删除房间 \"", deleteSuffix: "\" ？此操作不可撤销。", leavePrefix: "确定退出房间 \"",
    leaveSuffix: "\" ？",
  },
  "en-US": {
    title: "Room settings", avatarSuffix: " room avatar", roomName: "Room name", roomId: "Room ID",
    administrator: "Administrator", yes: "Yes", no: "No", maxFileSize: "Maximum file size",
    totalFileSpace: "Total file storage", maxFileCount: "Maximum file count", maxMembers: "Maximum room members",
    roomAvatar: "Room avatar", selectImage: "Choose image", selectNewAvatar: "Choose a new room avatar",
    uploading: "Uploading…", renameRoom: "Rename room", newName: "New name", modify: "Update",
    roomPassword: "Room password", passwordPlaceholder: "Leave empty to remove the password", set: "Set",
    checkStatus: "Check status", passwordSet: "Password is set (hidden; it can be replaced)",
    passwordNone: "No password is currently set", manageMembers: "Manage members", selectUser: "Select a user...",
    adminBadge: "[Administrator]", unsetAdmin: "Remove administrator", setAdmin: "Make administrator", kick: "Remove",
    messageManagement: "Message management", clearAll: "Clear all messages", danger: "Dangerous actions",
    deleteRoom: "Delete room", limitsTitle: "Limits (developer key required)", maxFileSizeGb: "Maximum file size (GB)",
    totalFileSpaceGb: "Total file storage (GB)", developerKey: "Developer key",
    developerKeyPlaceholder: "Enter the developer key to save limits", saveLimits: "Save limits",
    leaveRoom: "Leave room", close: "Close", avatarTooLarge: "The avatar is too large. Choose a smaller image.",
    avatarSaved: "Room avatar updated", nameSaved: "Room name updated", passwordSaved: "Room password set",
    passwordRemoved: "Room password removed", limitsSaved: "Room limits updated", limitsPositive: "Limits must be greater than zero.",
    totalTooSmall: "Total file storage cannot be smaller than the maximum file size.",
    developerKeyRequired: "Enter the developer key.", cleanupPrefix: "Changing these limits will clear ",
    cleanupFileSuffix: " historical file(s).\n", cleanupAfterPrefix: "After cleanup, approximately ",
    cleanupAfterMiddle: " file(s) and ", cleanupAfterSuffix: " GB will remain.\n",
    cleanupExpiry: "Cleared files remain in chat history and appear as expired or removed.\n\nContinue?",
    kickPrefix: "Remove ", kickSuffix: " from the room?", clearConfirm: "Clear all messages? This cannot be undone.",
    deletePrefix: "Delete room \"", deleteSuffix: "\"? This cannot be undone.", leavePrefix: "Leave room \"",
    leaveSuffix: "\"?",
  },
} as const;

const v2PreviewShellCatalog = {
  "zh-CN": {
    engineeringPreview: "V2 工程预览", loadingSecure: "正在加载安全连接组件", backV1Login: "返回 V1 登录",
    isolatedTest: "独立测试环境", loginTitle: "登录 V2", credentialsMemory: "凭据仅用于本次认证请求，不写入浏览器存储。",
    userId: "用户 ID", password: "密码", authenticating: "正在验证…", login: "登录", connectingSecure: "正在建立安全连接…",
    backStableV1: "返回稳定版 V1", conversationNavigation: "V2 会话导航", loginDevices: "登录设备",
    availableConversations: "可用会话", direct: "私聊", group: "群聊", noConversations: "当前没有可用会话",
    loadMoreConversations: "加载更多会话", messageRegion: "消息区域", selectConversation: "选择一个会话",
    cacheSync: "缓存消息会先显示，然后按服务器序列增量同步。", conversation: "会话", runtimeUnavailable: "V2 预览未启用",
    idle: "尚未连接", connecting: "连接中", negotiating: "协商协议中", connected: "可登录",
    resuming: "恢复会话中", authenticated: "已安全连接", offline: "网络离线", reconnectWait: "等待重连",
    stopped: "已停止", unknownState: "未知状态", syncing: "同步中…",
  },
  "en-US": {
    engineeringPreview: "V2 engineering preview", loadingSecure: "Loading secure connection components", backV1Login: "Back to V1 login",
    isolatedTest: "Isolated test environment", loginTitle: "Sign in to V2",
    credentialsMemory: "Credentials are used only for this authentication request and are not stored in the browser.",
    userId: "User ID", password: "Password", authenticating: "Authenticating…", login: "Sign in",
    connectingSecure: "Establishing a secure connection…", backStableV1: "Back to stable V1",
    conversationNavigation: "V2 conversation navigation", loginDevices: "Signed-in devices",
    availableConversations: "Available conversations", direct: "Direct", group: "Group",
    noConversations: "No conversations are available", loadMoreConversations: "Load more conversations",
    messageRegion: "Message area", selectConversation: "Select a conversation",
    cacheSync: "Cached messages appear first, followed by incremental server-sequence synchronization.",
    conversation: "Conversation", runtimeUnavailable: "V2 preview is unavailable", idle: "Not connected",
    connecting: "Connecting", negotiating: "Negotiating protocol", connected: "Ready to sign in",
    resuming: "Resuming session", authenticated: "Securely connected", offline: "Network offline",
    reconnectWait: "Waiting to reconnect", stopped: "Stopped", unknownState: "Unknown state", syncing: "Syncing…",
  },
} as const;

const v2PreviewSearchCatalog = {
  "zh-CN": {
    openSearch: "搜索消息", closeSearch: "关闭搜索", searchConversation: "搜索当前会话",
    exactText: "输入精确文字", searching: "搜索中…", search: "搜索", loadingContext: "正在加载消息上下文…",
    resultCount: (count: number) => `已找到 ${count} 条结果`, resultsLabel: "消息搜索结果", loadMore: "加载更多结果",
    invalidQuery: "请输入 1–128 字节的搜索文字", searchFailed: "无法搜索当前会话",
    loadMoreFailed: "无法加载更多结果", contextUnavailable: "该消息上下文暂不可用",
    contextFailed: "无法加载消息上下文", openResultFailed: "无法打开搜索结果", unavailable: "搜索暂不可用",
  },
  "en-US": {
    openSearch: "Search messages", closeSearch: "Close search", searchConversation: "Search this conversation",
    exactText: "Enter exact text", searching: "Searching…", search: "Search", loadingContext: "Loading message context…",
    resultCount: (count: number) => `${count} result${count === 1 ? "" : "s"} found`, resultsLabel: "Message search results",
    loadMore: "Load more results", invalidQuery: "Enter 1–128 bytes of search text", searchFailed: "Unable to search this conversation",
    loadMoreFailed: "Unable to load more results", contextUnavailable: "This message context is temporarily unavailable",
    contextFailed: "Unable to load message context", openResultFailed: "Unable to open the search result", unavailable: "Search is temporarily unavailable",
  },
} as const;

const v2PreviewTimelineCatalog = {
  "zh-CN": {
    history: "消息记录", pinned: "已置顶", forwarded: "已转发", reply: "回复", edited: "已编辑",
    replyLabel: (preview: string) => `回复：${preview}`, accountTitle: (accountId: string) => `账号 ${accountId}`,
    messageStatus: (sequence: string, state: string) => `消息 ${sequence}：${state}`,
    accepted: "已接收", sending: "发送中", failed: "发送失败",
    originalUnavailable: "原消息暂不可用", originalRecalled: "原消息已撤回",
  },
  "en-US": {
    history: "Message history", pinned: "Pinned", forwarded: "Forwarded", reply: "Reply", edited: "Edited",
    replyLabel: (preview: string) => `Reply: ${preview}`, accountTitle: (accountId: string) => `Account ${accountId}`,
    messageStatus: (sequence: string, state: string) => `Message ${sequence}: ${state}`,
    accepted: "Received", sending: "Sending", failed: "Send failed",
    originalUnavailable: "Original message is temporarily unavailable", originalRecalled: "Original message was recalled",
  },
} as const;

const v2PreviewBasicActionCatalog = {
  "zh-CN": {
    copy: "复制", copyLabel: (sequence: string) => `复制消息 ${sequence} 正文`,
    reply: "回复", replyLabel: (sequence: string) => `回复消息 ${sequence}`,
    retry: "重试", retryLabel: "重试这条发送失败的消息",
    copied: (sequence: string) => `消息 ${sequence} 正文已复制`,
    copyFailed: "无法复制消息正文，请检查浏览器权限",
    retryUnavailable: "该消息暂时无法重试", retryFailed: "消息重试失败",
  },
  "en-US": {
    copy: "Copy", copyLabel: (sequence: string) => `Copy message ${sequence} text`,
    reply: "Reply", replyLabel: (sequence: string) => `Reply to message ${sequence}`,
    retry: "Retry", retryLabel: "Retry this failed message",
    copied: (sequence: string) => `Message ${sequence} text copied`,
    copyFailed: "Unable to copy message text. Check browser permissions.",
    retryUnavailable: "This message cannot be retried right now", retryFailed: "Message retry failed",
  },
} as const;

const v2PreviewComposerCatalog = {
  "zh-CN": {
    replyingTo: (sequence: string) => `回复消息 #${sequence}`, cancelReply: "取消回复",
    cancelReplyTitle: "取消回复（Esc）", mentionMember: "@ 提及成员", sendTitle: "发送（Enter）",
    sendFailed: "消息发送失败",
  },
  "en-US": {
    replyingTo: (sequence: string) => `Replying to message #${sequence}`, cancelReply: "Cancel reply",
    cancelReplyTitle: "Cancel reply (Esc)", mentionMember: "@ Mention member", sendTitle: "Send (Enter)",
    sendFailed: "Message send failed",
  },
} as const;

const v2PreviewMentionCatalog = {
  "zh-CN": {
    title: "选择要提及的成员", close: "关闭成员选择器", retry: "重试", members: "会话成员",
    owner: "群主", admin: "管理员", member: "成员", loading: "正在加载成员…", loadMore: "加载更多成员",
    loadFailed: "无法加载成员", loadMoreFailed: "无法加载更多成员", insertFailed: "无法插入成员",
  },
  "en-US": {
    title: "Choose a member to mention", close: "Close member picker", retry: "Retry", members: "Conversation members",
    owner: "Owner", admin: "Administrator", member: "Member", loading: "Loading members…", loadMore: "Load more members",
    loadFailed: "Unable to load members", loadMoreFailed: "Unable to load more members", insertFailed: "Unable to insert the member mention",
  },
} as const;

const v2PreviewForwardCatalog = {
  "zh-CN": {
    forward: "转发", forwardLabel: (sequence: string) => `转发消息 ${sequence}`, title: "转发到会话",
    description: "服务器会复制最新的消息内容，不会暴露来源会话。", close: "关闭转发目标选择",
    targets: "转发目标会话", direct: "私聊", group: "群聊", forwarding: "正在保存并转发…",
    cacheUnavailable: "无法保存转发任务，已取消发送",
    retryInTarget: "转发任务暂未发送，可在目标会话中重试", failed: "转发失败",
  },
  "en-US": {
    forward: "Forward", forwardLabel: (sequence: string) => `Forward message ${sequence}`, title: "Forward to a conversation",
    description: "The server copies the latest message content without exposing the source conversation.", close: "Close forwarding target picker",
    targets: "Forwarding target conversations", direct: "Direct", group: "Group", forwarding: "Saving and forwarding…",
    cacheUnavailable: "The forwarding task could not be saved, so sending was cancelled",
    retryInTarget: "The forwarding task was not sent; retry it in the target conversation", failed: "Forwarding failed",
  },
} as const;

const v2PreviewDeviceCatalog = {
  "zh-CN": {
    title: "登录设备", description: "发现陌生设备时，可撤销它的全部登录会话。", close: "关闭登录设备",
    retry: "重试", reconnectNotice: "连接恢复后才能管理设备。", windows: "Windows 客户端", web: "Web 浏览器",
    currentDevice: "当前设备", recentActivity: (time: string) => `最近活动：${time}`, current: "当前", revoke: "撤销",
    confirmGroup: "确认撤销设备", revokeAll: "撤销全部会话？", revoking: "撤销中…", confirm: "确认", cancel: "取消",
    loading: "正在加载设备…", refresh: "刷新", done: "完成", loadFailed: "无法加载登录设备",
    refreshFailed: "无法刷新设备", revokeFailed: "无法撤销该设备", revokeUnavailable: "当前无法撤销该设备",
  },
  "en-US": {
    title: "Signed-in devices", description: "Revoke all sessions for a device you do not recognize.", close: "Close signed-in devices",
    retry: "Retry", reconnectNotice: "Reconnect before managing devices.", windows: "Windows client", web: "Web browser",
    currentDevice: "Current device", recentActivity: (time: string) => `Last active: ${time}`, current: "Current", revoke: "Revoke",
    confirmGroup: "Confirm device revocation", revokeAll: "Revoke all sessions?", revoking: "Revoking…", confirm: "Confirm", cancel: "Cancel",
    loading: "Loading devices…", refresh: "Refresh", done: "Done", loadFailed: "Unable to load signed-in devices",
    refreshFailed: "Unable to refresh devices", revokeFailed: "Unable to revoke this device", revokeUnavailable: "This device cannot be revoked right now",
  },
} as const;

const v2PreviewReactionCatalog = {
  "zh-CN": {
    groupLabel: (sequence: string) => `回应消息 ${sequence}`, countLabel: (label: string, count: number) => `${label}，${count} 人`,
    retryLabel: (sequence: string) => `重试消息 ${sequence} 的回应`, retry: "重试回应",
    like: "赞", love: "喜欢", laugh: "好笑", surprised: "惊讶", sad: "难过", angry: "生气",
    unavailable: "当前无法回应这条消息", failed: "回应失败",
    retryUnavailable: "该回应暂时无法重试", retryFailed: "回应重试失败",
  },
  "en-US": {
    groupLabel: (sequence: string) => `Reactions for message ${sequence}`, countLabel: (label: string, count: number) => `${label}, ${count} people`,
    retryLabel: (sequence: string) => `Retry the reaction for message ${sequence}`, retry: "Retry reaction",
    like: "Like", love: "Love", laugh: "Laugh", surprised: "Surprised", sad: "Sad", angry: "Angry",
    unavailable: "This message cannot be reacted to right now", failed: "Reaction failed",
    retryUnavailable: "This reaction cannot be retried right now", retryFailed: "Reaction retry failed",
  },
} as const;

const v2PreviewPinCatalog = {
  "zh-CN": {
    pin: "置顶", unpin: "取消置顶", actionLabel: (action: string, sequence: string) => `${action}消息 ${sequence}`,
    retryLabel: (sequence: string) => `重试消息 ${sequence} 的置顶操作`, retry: "重试置顶",
    unavailable: "当前无法置顶这条消息", failed: "置顶失败",
    retryUnavailable: "该置顶操作暂时无法重试", retryFailed: "置顶重试失败",
  },
  "en-US": {
    pin: "Pin", unpin: "Unpin", actionLabel: (action: string, sequence: string) => `${action} message ${sequence}`,
    retryLabel: (sequence: string) => `Retry the pin action for message ${sequence}`, retry: "Retry pin",
    unavailable: "This message cannot be pinned right now", failed: "Pin action failed",
    retryUnavailable: "This pin action cannot be retried right now", retryFailed: "Pin retry failed",
  },
} as const;

export type LoginMessageKey = keyof typeof loginCatalog["zh-CN"];

export function resolveWebLocale(storage: WebLocaleStorage | null | undefined): WebLocale {
  try {
    const value = storage?.getItem(STORAGE_KEY);
    return SUPPORTED_WEB_LOCALES.includes(value as WebLocale) ? value as WebLocale : "zh-CN";
  } catch {
    return "zh-CN";
  }
}

export function persistWebLocale(
  storage: WebLocaleStorage | null | undefined,
  locale: string,
): locale is WebLocale {
  if (!SUPPORTED_WEB_LOCALES.includes(locale as WebLocale)) return false;
  try {
    storage?.setItem(STORAGE_KEY, locale);
    return Boolean(storage);
  } catch {
    return false;
  }
}

export function loginMessages(locale: WebLocale) {
  return loginCatalog[locale];
}

export function chatShellMessages(locale: WebLocale) {
  return shellCatalog[locale];
}

export function profileMessages(locale: WebLocale) {
  return profileCatalog[locale];
}

export function friendListMessages(locale: WebLocale) {
  return friendListCatalog[locale];
}

export function roomListMessages(locale: WebLocale) {
  return roomListCatalog[locale];
}

export function memberListMessages(locale: WebLocale) {
  return memberListCatalog[locale];
}

export function composerMessages(locale: WebLocale) {
  return composerCatalog[locale];
}

export function emojiPickerMessages(locale: WebLocale) {
  return emojiPickerCatalog[locale];
}

export function messageTimelineMessages(locale: WebLocale) {
  return messageTimelineCatalog[locale];
}

export function messageAttachmentMessages(locale: WebLocale) {
  return messageAttachmentCatalog[locale];
}

export function messageActionMessages(locale: WebLocale) {
  return messageActionCatalog[locale];
}

export function filePreviewMessages(locale: WebLocale) {
  return filePreviewCatalog[locale];
}

export function forwardDialogMessages(locale: WebLocale) {
  return forwardDialogCatalog[locale];
}

export function downloadPanelMessages(locale: WebLocale) {
  return downloadPanelCatalog[locale];
}

export function userInfoMessages(locale: WebLocale) {
  return userInfoCatalog[locale];
}

export function roomPasswordMessages(locale: WebLocale) {
  return roomPasswordCatalog[locale];
}

export function roomFileManagerMessages(locale: WebLocale) {
  return roomFileManagerCatalog[locale];
}

export function roomSettingsMessages(locale: WebLocale) {
  return roomSettingsCatalog[locale];
}

export function v2PreviewShellMessages(locale: WebLocale) {
  return v2PreviewShellCatalog[locale];
}

export function v2PreviewSearchMessages(locale: WebLocale) {
  return v2PreviewSearchCatalog[locale];
}

export function v2PreviewTimelineMessages(locale: WebLocale) {
  return v2PreviewTimelineCatalog[locale];
}

export function v2PreviewBasicActionMessages(locale: WebLocale) {
  return v2PreviewBasicActionCatalog[locale];
}

export function v2PreviewComposerMessages(locale: WebLocale) {
  return v2PreviewComposerCatalog[locale];
}

export function v2PreviewMentionMessages(locale: WebLocale) {
  return v2PreviewMentionCatalog[locale];
}

export function v2PreviewForwardMessages(locale: WebLocale) {
  return v2PreviewForwardCatalog[locale];
}

export function v2PreviewDeviceMessages(locale: WebLocale) {
  return v2PreviewDeviceCatalog[locale];
}

export function v2PreviewReactionMessages(locale: WebLocale) {
  return v2PreviewReactionCatalog[locale];
}

export function v2PreviewPinMessages(locale: WebLocale) {
  return v2PreviewPinCatalog[locale];
}

export function applyDocumentLocale(locale: WebLocale, root?: { lang: string } | null): void {
  if (root) root.lang = locale;
}
