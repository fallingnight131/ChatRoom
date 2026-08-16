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

export function applyDocumentLocale(locale: WebLocale, root?: { lang: string } | null): void {
  if (root) root.lang = locale;
}
