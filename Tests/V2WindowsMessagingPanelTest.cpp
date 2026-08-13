#include "V2WindowsMessagingPanel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsMessagingViewModel.h"

#include <QApplication>
#include <QListWidget>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTimer>
#include <QDebug>
#include <algorithm>

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    const QString account = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message message;
    message.conversationId = conversation;
    message.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    message.senderAccountId = account;
    message.clientMessageId = QStringLiteral("remote-1");
    message.text = QStringLiteral("hello");
    message.state = V2LocalMessageRepository::DeliveryState::Accepted;
    snapshot.messages.append(message);
    QList<V2LocalMessageRepository::Mention> submittedMentions;
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &, const QString &,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
                submittedMentions = mentions;
                return true;
            },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, V2LocalMessageRepository::ReactionKind) {
            return true;
        },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, const QString &,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &) { return true; });
    int participantRequests = 0;
    V2WindowsConversationParticipantViewModel participants(
        [&](const QString &requestedConversation, bool continuation) {
            ++participantRequests;
            return requestedConversation == conversation && !continuation;
        });
    model.openConversation(conversation);
    V2WindowsMessagingPanel defaultOff(&model, &participants);
    if (!defaultOff.mentionForTest()->isHidden()) return 1;
    V2WindowsMessagingPanel panel(&model, &participants, nullptr, true);
    panel.setConversation(conversation);
    panel.show();
    if (panel.accessibleName().isEmpty()
            || panel.messageListForTest()->accessibleName().isEmpty()
            || panel.composerForTest()->accessibleName().isEmpty()
            || panel.sendForTest()->accessibleName().isEmpty()) return 1;
    auto replies = panel.findChildren<QPushButton *>(QString(), Qt::FindChildrenRecursively);
    auto reply = std::find_if(replies.cbegin(), replies.cend(), [](QPushButton *button) {
        return button->text() == QStringLiteral("回复");
    });
    if (reply == replies.cend()) return 1;
    const auto reactionButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isCheckable()
                && button->accessibleName().startsWith(QStringLiteral("消息反应"));
        });
    if (reactionButtons != 6) return 1;
    const auto pinButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isCheckable()
                && button->accessibleName() == QStringLiteral("置顶此消息");
        });
    if (pinButtons != 1) return 1;
    const auto editButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) { return button->accessibleName() == QStringLiteral("编辑此消息"); });
    if (editButtons != 1) return 1;
    (*reply)->click();
    app.processEvents();
    if (!panel.cancelReplyForTest()->isVisible()
            || model.replyTargetMessageId() != message.messageId) return 1;
    panel.composerForTest()->setPlainText(QStringLiteral("reply text"));
    app.processEvents();
    if (!panel.sendForTest()->isEnabled()) return 1;
    panel.cancelReplyForTest()->click();
    app.processEvents();
    if (!model.replyTargetMessageId().isEmpty()) return 1;
    (*reply)->click();
    panel.composerForTest()->clear();
    panel.mentionForTest()->click();
    app.processEvents();
    if (participantRequests != 1 || !participants.busy()) return 1;
    participants.applyPage(conversation, {{
        account, QStringLiteral("张三😀"), QStringLiteral("成员")}}, false, false);
    app.processEvents();
    if (panel.participantListForTest()->count() != 1
            || panel.participantListForTest()->accessibleName().isEmpty()) return 1;
    panel.participantListForTest()->itemActivated(
        panel.participantListForTest()->item(0));
    app.processEvents();
    if (panel.composerForTest()->toPlainText() != QStringLiteral("@张三😀 ")) return 1;
    panel.sendForTest()->click();
    app.processEvents();
    if (submittedMentions.size() != 1
            || submittedMentions.first().targetAccountId != account
            || submittedMentions.first().startUtf8Byte != 0
            || submittedMentions.first().lengthUtf8Bytes
                != QStringLiteral("@张三😀").toUtf8().size()) return 1;
    qInfo() << "[V2WindowsMessagingPanelTest] PASS";
    return 0;
}
