#include "V2WindowsConversationDialog.h"

#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QListWidget>
#include <QPushButton>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}
}

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    const QString accountId = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString conversationId = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message message;
    message.conversationId = conversationId;
    message.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    message.senderAccountId = QStringLiteral("10000000-0000-4000-8000-000000000002");
    message.clientMessageId = QStringLiteral("remote-1");
    message.text = QStringLiteral("hello from V2");
    message.state = V2LocalMessageRepository::DeliveryState::Accepted;
    snapshot.messages.append(message);
    V2WindowsMessagingViewModel messaging(
        accountId, [&](const QString &) { return snapshot; },
        [](const QString &, const QString &,
           V2LocalMessageRepository::Message *,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &, const QString &,
           V2LocalMessageRepository::Message *,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &,
           V2LocalMessageRepository::ReactionKind) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, const QString &,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &) { return true; });

    int refreshCalls = 0;
    int loadMoreCalls = 0;
    int openCalls = 0;
    V2WindowsConversationDirectoryViewModel directory(
        [&] { ++refreshCalls; return true; },
        [&] { ++loadMoreCalls; return true; },
        [&](const QString &selected) -> bool {
            ++openCalls;
            return messaging.openConversation(selected);
        });
    directory.applyPage({{
        conversationId, QStringLiteral("工程群"), QStringLiteral("群聊"),
        QStringLiteral("成员"), 3}}, false, true);
    int participantRequests = 0;
    V2WindowsConversationParticipantViewModel participants(
        [&](const QString &selected, bool continuation) {
            ++participantRequests;
            return selected == conversationId && !continuation;
        });

    V2WindowsConversationDialog dialog(
        &directory, &messaging, &participants, nullptr, true);
    dialog.show();
    app.processEvents();
    if (!check(!dialog.accessibleName().isEmpty(),
               QStringLiteral("dialog must have an accessible name"))
            || !check(!dialog.conversationListForTest()->accessibleName().isEmpty(),
                      QStringLiteral("directory must have an accessible name"))
            || !check(dialog.conversationListForTest()->count() == 1
                          && dialog.conversationListForTest()->item(0)->text().contains(
                              QStringLiteral("3 条未读")),
                      QStringLiteral("directory must render name and unread count"))
            || !check(dialog.loadMoreForTest()->isEnabled(),
                      QStringLiteral("validated continuation must enable load more"))) return 1;

    dialog.conversationListForTest()->setCurrentRow(0);
    app.processEvents();
    if (!check(openCalls == 1,
               QStringLiteral("selecting a row must open its hidden authorized identity"))
            || !check(dialog.messagingPanelForTest()->isEnabled()
                          && dialog.messagingPanelForTest()->messageListForTest()->count() == 1,
                      QStringLiteral("opened conversation must render cached messages"))) return 1;
    if (!check(participantRequests == 0,
               QStringLiteral("opening a conversation must not prefetch picker data"))) return 1;
    messaging.chooseReply(message.messageId);
    app.processEvents();
    dialog.messagingPanelForTest()->mentionForTest()->click();
    app.processEvents();
    if (!check(participantRequests == 1 && participants.busy(),
               QStringLiteral("opening the mention picker must explicitly request members")))
        return 1;

    dialog.loadMoreForTest()->click();
    app.processEvents();
    if (!check(loadMoreCalls == 1 && directory.busy(),
               QStringLiteral("load more must own one in-flight request"))) return 1;
    directory.applyPage({}, true, false);
    dialog.refreshForTest()->click();
    app.processEvents();
    if (!check(refreshCalls == 1 && directory.busy(),
               QStringLiteral("refresh must remain reachable after paging"))) return 1;

    qInfo() << "[V2WindowsConversationDialogTest] PASS";
    return 0;
}
