#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
int failures = 0;

void check(bool condition, const QString &message) {
    if (condition) return;
    ++failures;
    qCritical().noquote() << message;
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    const QString conversation =
        QStringLiteral("20000000-0000-4000-8000-000000000001");

    V2WindowsConversationDirectoryViewModel directory(
        [] { return false; }, [] { return false; },
        [](const QString &) { return false; }, nullptr, WindowsLocale::EnUs);
    check(!directory.refresh()
              && directory.failure() == QStringLiteral("Unable to refresh conversations"),
          QStringLiteral("English directory refresh failure was not projected"));
    directory.applyPage({{
        conversation, QStringLiteral("Project"), QStringLiteral("Group"),
        QStringLiteral("Member"), 0, false}}, false, true);
    check(!directory.loadMore()
              && directory.failure()
                  == QStringLiteral("Unable to load more conversations"),
          QStringLiteral("English directory continuation failure was not projected"));
    directory.setUnavailable();
    check(directory.failure()
              == QStringLiteral("Conversation service disconnected; reconnecting"),
          QStringLiteral("English directory unavailable state was not projected"));

    V2WindowsConversationParticipantViewModel participants(
        [](const QString &, bool) { return false; },
        nullptr, WindowsLocale::EnUs);
    check(!participants.activate(conversation)
              && participants.failure() == QStringLiteral("Unable to refresh members"),
          QStringLiteral("English participant refresh failure was not projected"));
    participants.applyPage(conversation, {{
        QStringLiteral("10000000-0000-4000-8000-000000000001"),
        QStringLiteral("Alice"), QStringLiteral("Member")}}, false, true);
    check(!participants.loadMore()
              && participants.failure() == QStringLiteral("Unable to load more members"),
          QStringLiteral("English participant continuation failure was not projected"));
    participants.setUnavailable();
    check(participants.failure()
              == QStringLiteral("Member service disconnected; reconnecting"),
          QStringLiteral("English participant unavailable state was not projected"));

    V2WindowsMessageSearchViewModel search(
        [](const QString &, const QString &, quint64, bool) { return false; },
        [](const QString &, quint64, const QString &) { return false; },
        nullptr, WindowsLocale::EnUs);
    check(search.activate(conversation),
          QStringLiteral("English search did not activate"));
    check(!search.search(QStringLiteral("hello"))
              && search.failure() == QStringLiteral("Unable to search messages"),
          QStringLiteral("English search failure was not projected"));
    search.applyPage(conversation, QStringLiteral("hello"), {{
        QStringLiteral("30000000-0000-4000-8000-000000000001"), 10,
        QStringLiteral("10000000-0000-4000-8000-000000000001"),
        QStringLiteral("hello"), 1, 0, 0}}, false, 9, true);
    check(!search.requestContext(
              QStringLiteral("30000000-0000-4000-8000-000000000001"))
              && search.failure() == QStringLiteral("Unable to load message context"),
          QStringLiteral("English context failure was not projected"));
    check(!search.loadMore()
              && search.failure() == QStringLiteral("Unable to load more search results"),
          QStringLiteral("English search continuation failure was not projected"));
    search.setUnavailable();
    check(search.failure()
              == QStringLiteral("Search service disconnected; reconnect and try again"),
          QStringLiteral("English search unavailable state was not projected"));

    if (failures) return 1;
    qInfo() << "[WindowsConversationRuntimeLocalizationTest] PASS";
    return 0;
}
