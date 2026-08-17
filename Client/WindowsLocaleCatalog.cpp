#include "WindowsLocaleCatalog.h"

namespace {
const WindowsLocaleMessages ZhCn{
    QStringLiteral("语言"), QStringLiteral("简体中文"), QStringLiteral("English"),
    QStringLiteral("新版会话与回复（预览）"), QStringLiteral("会话"),
    QStringLiteral("请选择会话"), QStringLiteral("刷新"), QStringLiteral("加载更多"),
    QStringLiteral("消息和回复"), QStringLiteral("消息列表"), QStringLiteral("消息内容"),
    QStringLiteral("输入消息"), QStringLiteral("@ 提及"), QStringLiteral("取消回复"),
    QStringLiteral("发送消息"), QStringLiteral("%1 / %2 字节"),
    QStringLiteral("超过上限 %1 字节（最多 %2 字节）"),
};

const WindowsLocaleMessages EnUs{
    QStringLiteral("Language"), QStringLiteral("简体中文"), QStringLiteral("English"),
    QStringLiteral("Conversations and replies (Preview)"),
    QStringLiteral("Conversations"), QStringLiteral("Select a conversation"),
    QStringLiteral("Refresh"), QStringLiteral("Load more"),
    QStringLiteral("Messages and replies"), QStringLiteral("Message list"),
    QStringLiteral("Message"), QStringLiteral("Write a message"),
    QStringLiteral("@ Mention"), QStringLiteral("Cancel reply"),
    QStringLiteral("Send message"), QStringLiteral("%1 / %2 bytes"),
    QStringLiteral("%1 bytes over the limit (%2 maximum)"),
};
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
