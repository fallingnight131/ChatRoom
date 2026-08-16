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

export function applyDocumentLocale(locale: WebLocale, root?: { lang: string } | null): void {
  if (root) root.lang = locale;
}
