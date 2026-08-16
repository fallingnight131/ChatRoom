#include "WindowsMessageNotificationPolicy.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const char *failure) {
    if (!condition) qCritical().noquote() << failure;
    return condition;
}

WindowsMessageNotificationPolicy::IncomingMessage message(
        const QString &messageId, const QString &conversationId,
        bool mentioned = false) {
    return {messageId, conversationId,
        QStringLiteral("30000000-0000-4000-8000-000000000001"),
        mentioned};
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    WindowsMessageNotificationPolicy policy(2);

    const QString conversation =
        QStringLiteral("20000000-0000-4000-8000-000000000001");
    const auto hidden = policy.evaluate(
        message(QStringLiteral("10000000-0000-4000-8000-000000000001"), conversation),
        {false, {}});
    if (!check(hidden.show && hidden.title == QStringLiteral("新消息")
            && hidden.body == QStringLiteral("打开聊天软件查看消息"),
            "background notification must use a privacy-safe body")) return 1;

    const auto duplicate = policy.evaluate(
        message(QStringLiteral("10000000-0000-4000-8000-000000000001"), conversation),
        {false, {}});
    if (!check(!duplicate.show && policy.rememberedMessageCount() == 1,
            "stable message duplicate must not notify twice")) return 1;

    const auto visible = policy.evaluate(
        message(QStringLiteral("10000000-0000-4000-8000-000000000002"), conversation),
        {true, conversation});
    if (!check(!visible.show && policy.rememberedMessageCount() == 2,
            "active visible conversation must be silent and remembered")) return 1;

    const auto otherConversation = policy.evaluate(
        message(QStringLiteral("10000000-0000-4000-8000-000000000003"),
                QStringLiteral("20000000-0000-4000-8000-000000000002"), true),
        {true, conversation});
    if (!check(otherConversation.show
            && otherConversation.title == QStringLiteral("有人提到了你")
            && policy.rememberedMessageCount() == 2,
            "another conversation mention must notify within the memory bound")) return 1;

    const auto evicted = policy.evaluate(
        message(QStringLiteral("10000000-0000-4000-8000-000000000001"), conversation),
        {false, {}});
    if (!check(evicted.show && policy.rememberedMessageCount() == 2,
            "oldest identity must be evicted from the bounded deduplication set")) return 1;

    const auto malformed = policy.evaluate({}, {false, {}});
    if (!check(!malformed.show && policy.rememberedMessageCount() == 2,
            "malformed event must not consume deduplication capacity")) return 1;

    policy.clear();
    if (!check(policy.rememberedMessageCount() == 0,
            "session reset must clear notification deduplication state")) return 1;
    return 0;
}
