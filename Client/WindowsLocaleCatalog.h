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
    QString conversationDirectory;
    QString selectConversation;
    QString refresh;
    QString loadMore;
    QString messagePanel;
    QString messageList;
    QString composer;
    QString composerPlaceholder;
    QString mention;
    QString cancelReply;
    QString sendMessage;
    QString bytesUsed;
    QString bytesOverLimit;
};

class WindowsLocaleCatalog final {
public:
    static WindowsLocale defaultLocale();
    static std::optional<WindowsLocale> parse(const QString &code);
    static QString code(WindowsLocale locale);
    static const WindowsLocaleMessages &messages(WindowsLocale locale);
};
