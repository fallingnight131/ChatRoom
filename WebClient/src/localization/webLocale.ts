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

export function applyDocumentLocale(locale: WebLocale, root?: { lang: string } | null): void {
  if (root) root.lang = locale;
}
