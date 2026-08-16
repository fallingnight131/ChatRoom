#include "WindowsMessageNotificationPresenter.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const char *failure) {
    if (!condition) qCritical().noquote() << failure;
    return condition;
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QString presentedConversation;
    QString activatedConversation;
    int showCount = 0;
    WindowsMessageNotificationPresenter presenter(
        [&](const QString &title, const QString &body, const QString &conversationId) {
            ++showCount;
            presentedConversation = conversationId;
            return title == QStringLiteral("新消息") && !body.isEmpty();
        },
        [&](const QString &conversationId) {
            activatedConversation = conversationId;
            return true;
        });

    const QString conversation =
        QStringLiteral("20000000-0000-4000-8000-000000000001");
    const WindowsMessageNotificationPolicy::IncomingMessage incoming{
        QStringLiteral("10000000-0000-4000-8000-000000000001"), conversation,
        QStringLiteral("30000000-0000-4000-8000-000000000001"), false};
    if (!check(presenter.present(incoming, {false, {}})
            && showCount == 1 && presentedConversation == conversation,
            "eligible message did not reach the platform presenter")) return 1;
    if (!check(!presenter.present(incoming, {false, {}}) && showCount == 1,
            "duplicate message reached the platform presenter")) return 1;
    if (!check(presenter.activate(presentedConversation)
            && activatedConversation == conversation,
            "notification activation lost the stable conversation identity")) return 1;
    if (!check(!presenter.activate({}),
            "empty activation identity reached conversation routing")) return 1;

    presenter.clearSession();
    if (!check(presenter.present(incoming, {false, {}}) && showCount == 2,
            "session reset did not clear bounded notification identity state")) return 1;
    return 0;
}
