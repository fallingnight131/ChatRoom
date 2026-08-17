#pragma once

#include <QString>
#include <optional>

enum class WindowsLocale {
    ZhCn,
    EnUs,
};

struct WindowsLocaleMessages {
    QString language;
    QString chinese;
    QString english;
    QString previewWindowTitle;
    QString previewWindowAccessible;
    QString conversationDirectory;
    QString conversationDirectoryAccessible;
    QString conversationDirectoryStatusAccessible;
    QString conversationListAccessible;
    QString selectConversation;
    QString refresh;
    QString refreshConversationsAccessible;
    QString loadMore;
    QString loadMoreConversationsAccessible;
    QString currentConversationAccessible;
    QString accountBlock;
    QString accountBlockAccessible;
    QString conversationSplitterAccessible;
    QString unreadCount;
    QString loadingConversations;
    QString noConversations;
    QString messagePanel;
    QString messageStatusAccessible;
    QString messageList;
    QString composer;
    QString composerPlaceholder;
    QString mention;
    QString mentionAccessible;
    QString cancelReply;
    QString cancelReplyAccessible;
    QString cancelEdit;
    QString cancelEditAccessible;
    QString editingMessage;
    QString sendMessage;
    QString sendMessageAccessible;
    QString sendReply;
    QString sendReplyAccessible;
    QString saveEdit;
    QString saveEditAccessible;
    QString composerBudgetAccessible;
    QString bytesUsed;
    QString bytesOverLimit;
    QString loadLocalMessagesFailed;
    QString replyBanner;
    QString sendMessageFailed;
    QString sentButDraftClearFailed;
    QString sendReplyFailed;
    QString replySentButDraftClearFailed;
    QString saveDraftFailed;
    QString retryMessageFailed;
    QString updateReactionFailed;
    QString retryReactionFailed;
    QString updatePinFailed;
    QString retryPinFailed;
    QString editMessageFailed;
    QString retryEditFailed;
    QString newerVersionUnavailable;
    QString discardEditFailed;
    QString forwardMessageFailed;
    QString recalledMessage;
    QString sending;
    QString sendFailed;
    QString replyUnavailable;
    QString replyRecalled;
};

class WindowsLocaleCatalog final {
public:
    static WindowsLocale defaultLocale();
    static std::optional<WindowsLocale> parse(const QString &code);
    static QString code(WindowsLocale locale);
    static const WindowsLocaleMessages &messages(WindowsLocale locale);
};
