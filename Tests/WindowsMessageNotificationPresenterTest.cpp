#include "WindowsMessageNotificationPresenter.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QSettings>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const char *failure) {
    if (!condition) qCritical().noquote() << failure;
    return condition;
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    QSettings settings(
        QDir(temporary.path()).filePath(QStringLiteral("ui.ini")),
        QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel locale(&repository);
    QString presentedConversation;
    QString activatedConversation;
    QString presentedTitle;
    QString presentedBody;
    int showCount = 0;
    WindowsMessageNotificationPresenter presenter(
        [&](const QString &title, const QString &body, const QString &conversationId) {
            ++showCount;
            presentedConversation = conversationId;
            presentedTitle = title;
            presentedBody = body;
            return !title.isEmpty() && !body.isEmpty();
        },
        [&](const QString &conversationId) {
            activatedConversation = conversationId;
            return true;
        }, 256, &locale);

    const QString conversation =
        QStringLiteral("20000000-0000-4000-8000-000000000001");
    const WindowsMessageNotificationPolicy::IncomingMessage incoming{
        QStringLiteral("10000000-0000-4000-8000-000000000001"), conversation,
        QStringLiteral("30000000-0000-4000-8000-000000000001"), false};
    if (!check(presenter.present(incoming, {false, {}})
            && showCount == 1 && presentedConversation == conversation
            && presentedTitle == QStringLiteral("新消息")
            && presentedBody == QStringLiteral("打开聊天软件查看消息"),
            "eligible message did not reach the platform presenter")) return 1;
    if (!check(!presenter.present(incoming, {false, {}}) && showCount == 1,
            "duplicate message reached the platform presenter")) return 1;
    if (!check(presenter.activate(presentedConversation)
            && activatedConversation == conversation,
            "notification activation lost the stable conversation identity")) return 1;
    if (!check(!presenter.activate({}),
            "empty activation identity reached conversation routing")) return 1;

    presenter.clearSession();
    if (!locale.select(WindowsLocale::EnUs)) return 1;
    if (!check(presenter.present(incoming, {false, {}}) && showCount == 2
            && presentedTitle == QStringLiteral("New message")
            && presentedBody == QStringLiteral("Open the chat app to view the message"),
            "session reset or current-locale generic projection failed")) return 1;
    const WindowsMessageNotificationPolicy::IncomingMessage mention{
        QStringLiteral("10000000-0000-4000-8000-000000000002"), conversation,
        QStringLiteral("30000000-0000-4000-8000-000000000001"), true};
    if (!check(presenter.present(mention, {false, {}}) && showCount == 3
            && presentedTitle == QStringLiteral("You were mentioned")
            && presentedBody == QStringLiteral("Open the chat app to view the message"),
            "current locale did not project privacy-safe mention notification")) return 1;
    return 0;
}
