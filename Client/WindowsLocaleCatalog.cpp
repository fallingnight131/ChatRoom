#include "WindowsLocaleCatalog.h"

namespace {
WindowsLocaleMessages createZhCn() {
    WindowsLocaleMessages m;
    m.language = QStringLiteral("语言");
    m.chinese = QStringLiteral("简体中文");
    m.english = QStringLiteral("English");
    m.previewWindowTitle = QStringLiteral("新版会话与回复（预览）");
    m.previewWindowAccessible = QStringLiteral("新版会话与回复预览窗口");
    m.conversationDirectory = QStringLiteral("会话");
    m.conversationDirectoryAccessible = QStringLiteral("会话目录标题");
    m.conversationDirectoryStatusAccessible = QStringLiteral("会话目录状态");
    m.conversationListAccessible = QStringLiteral("新版会话列表");
    m.selectConversation = QStringLiteral("请选择会话");
    m.refresh = QStringLiteral("刷新");
    m.refreshConversationsAccessible = QStringLiteral("刷新新版会话列表");
    m.loadMore = QStringLiteral("加载更多");
    m.loadMoreConversationsAccessible = QStringLiteral("加载更多新版会话");
    m.currentConversationAccessible = QStringLiteral("当前新版会话");
    m.accountBlock = QStringLiteral("屏蔽管理");
    m.accountBlockAccessible = QStringLiteral("管理当前私聊账号屏蔽状态");
    m.conversationSplitterAccessible = QStringLiteral("会话与消息分栏");
    m.unreadCount = QStringLiteral("%1 条未读");
    m.loadingConversations = QStringLiteral("正在加载会话…");
    m.noConversations = QStringLiteral("暂无会话");
    m.messagePanel = QStringLiteral("消息和回复");
    m.messageStatusAccessible = QStringLiteral("消息状态");
    m.messageList = QStringLiteral("消息列表");
    m.composer = QStringLiteral("消息内容");
    m.composerPlaceholder = QStringLiteral("输入消息");
    m.mention = QStringLiteral("@ 提及");
    m.mentionAccessible = QStringLiteral("打开会话成员选择器");
    m.cancelReply = QStringLiteral("取消回复");
    m.cancelReplyAccessible = QStringLiteral("取消当前回复");
    m.cancelEdit = QStringLiteral("取消编辑");
    m.cancelEditAccessible = QStringLiteral("取消当前编辑");
    m.editingMessage = QStringLiteral("正在编辑消息");
    m.sendMessage = QStringLiteral("发送消息");
    m.sendMessageAccessible = QStringLiteral("发送当前消息");
    m.sendReply = QStringLiteral("发送回复");
    m.sendReplyAccessible = QStringLiteral("发送当前回复");
    m.saveEdit = QStringLiteral("保存编辑");
    m.saveEditAccessible = QStringLiteral("保存当前消息编辑");
    m.composerBudgetAccessible = QStringLiteral("消息字节数");
    m.bytesUsed = QStringLiteral("%1 / %2 字节");
    m.bytesOverLimit = QStringLiteral("超过上限 %1 字节（最多 %2 字节）");
    return m;
}

WindowsLocaleMessages createEnUs() {
    WindowsLocaleMessages m;
    m.language = QStringLiteral("Language");
    m.chinese = QStringLiteral("简体中文");
    m.english = QStringLiteral("English");
    m.previewWindowTitle = QStringLiteral("Conversations and replies (Preview)");
    m.previewWindowAccessible = QStringLiteral("Conversations and replies preview window");
    m.conversationDirectory = QStringLiteral("Conversations");
    m.conversationDirectoryAccessible = QStringLiteral("Conversation directory heading");
    m.conversationDirectoryStatusAccessible = QStringLiteral("Conversation directory status");
    m.conversationListAccessible = QStringLiteral("Conversation list");
    m.selectConversation = QStringLiteral("Select a conversation");
    m.refresh = QStringLiteral("Refresh");
    m.refreshConversationsAccessible = QStringLiteral("Refresh conversation list");
    m.loadMore = QStringLiteral("Load more");
    m.loadMoreConversationsAccessible = QStringLiteral("Load more conversations");
    m.currentConversationAccessible = QStringLiteral("Current conversation");
    m.accountBlock = QStringLiteral("Blocking");
    m.accountBlockAccessible = QStringLiteral("Manage blocking for this direct conversation");
    m.conversationSplitterAccessible = QStringLiteral("Conversation and message panes");
    m.unreadCount = QStringLiteral("%1 unread");
    m.loadingConversations = QStringLiteral("Loading conversations…");
    m.noConversations = QStringLiteral("No conversations");
    m.messagePanel = QStringLiteral("Messages and replies");
    m.messageStatusAccessible = QStringLiteral("Message status");
    m.messageList = QStringLiteral("Message list");
    m.composer = QStringLiteral("Message");
    m.composerPlaceholder = QStringLiteral("Write a message");
    m.mention = QStringLiteral("@ Mention");
    m.mentionAccessible = QStringLiteral("Open conversation member picker");
    m.cancelReply = QStringLiteral("Cancel reply");
    m.cancelReplyAccessible = QStringLiteral("Cancel current reply");
    m.cancelEdit = QStringLiteral("Cancel edit");
    m.cancelEditAccessible = QStringLiteral("Cancel current edit");
    m.editingMessage = QStringLiteral("Editing message");
    m.sendMessage = QStringLiteral("Send message");
    m.sendMessageAccessible = QStringLiteral("Send current message");
    m.sendReply = QStringLiteral("Send reply");
    m.sendReplyAccessible = QStringLiteral("Send current reply");
    m.saveEdit = QStringLiteral("Save edit");
    m.saveEditAccessible = QStringLiteral("Save current message edit");
    m.composerBudgetAccessible = QStringLiteral("Message byte count");
    m.bytesUsed = QStringLiteral("%1 / %2 bytes");
    m.bytesOverLimit = QStringLiteral("%1 bytes over the limit (%2 maximum)");
    return m;
}

const WindowsLocaleMessages ZhCn = createZhCn();
const WindowsLocaleMessages EnUs = createEnUs();
}

WindowsLocale WindowsLocaleCatalog::defaultLocale() {
    return WindowsLocale::ZhCn;
}

std::optional<WindowsLocale> WindowsLocaleCatalog::parse(const QString &code) {
    if (code == QStringLiteral("zh-CN")) return WindowsLocale::ZhCn;
    if (code == QStringLiteral("en-US")) return WindowsLocale::EnUs;
    return std::nullopt;
}

QString WindowsLocaleCatalog::code(WindowsLocale locale) {
    switch (locale) {
    case WindowsLocale::ZhCn:
        return QStringLiteral("zh-CN");
    case WindowsLocale::EnUs:
        return QStringLiteral("en-US");
    }
    return QStringLiteral("zh-CN");
}

const WindowsLocaleMessages &WindowsLocaleCatalog::messages(WindowsLocale locale) {
    return locale == WindowsLocale::EnUs ? EnUs : ZhCn;
}
