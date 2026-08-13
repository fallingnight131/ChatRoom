#include "V2WindowsMessagingViewModel.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
int failures = 0;
void check(bool value, const QString &message) {
    if (!value) { ++failures; qCritical().noquote() << message; }
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    const QString account = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message target;
    target.conversationId = conversation;
    target.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    target.conversationSequence = 1;
    target.senderAccountId = QStringLiteral("10000000-0000-4000-8000-000000000002");
    target.clientMessageId = QStringLiteral("remote-1");
    target.text = QStringLiteral("line one\nline two");
    target.state = V2LocalMessageRepository::DeliveryState::Accepted;
    snapshot.messages.append(target);
    auto reply = target;
    reply.messageId = QStringLiteral("30000000-0000-4000-8000-000000000002");
    reply.conversationSequence = 2;
    reply.clientMessageId = QStringLiteral("mine-1");
    reply.senderAccountId = account;
    reply.text = QStringLiteral("reply");
    reply.hasReply = true;
    reply.reply = {target.messageId, 1, target.senderAccountId};
    snapshot.messages.append(reply);
    auto failed = reply;
    failed.messageId.clear();
    failed.conversationSequence = 0;
    failed.clientMessageId = QStringLiteral("failed-1");
    failed.state = V2LocalMessageRepository::DeliveryState::Failed;
    snapshot.messages.append(failed);
    snapshot.messages[0].reactions.append({
        V2LocalMessageRepository::ReactionKind::Like, {account}});
    V2LocalMessageRepository::ReactionCommand failedReaction;
    failedReaction.conversationId = conversation;
    failedReaction.messageId = target.messageId;
    failedReaction.reaction = V2LocalMessageRepository::ReactionKind::Love;
    failedReaction.clientOperationId = QStringLiteral("reaction-failed-1");
    failedReaction.state = V2LocalMessageRepository::DeliveryState::Failed;
    snapshot.reactionCommands.append(failedReaction);

    QString stagedTarget;
    QString stagedText;
    QString retried;
    QString reactedMessage;
    QString retriedReaction;
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &targetId, const QString &text,
            V2LocalMessageRepository::Message *) {
            stagedTarget = targetId; stagedText = text; return true;
        },
        [&](const QString &, const QString &clientId) { retried = clientId; return true; },
        [&](const QString &, const QString &messageId,
            V2LocalMessageRepository::ReactionKind) {
            reactedMessage = messageId; return true;
        },
        [&](const QString &, const QString &operationId) {
            retriedReaction = operationId; return true;
        });
    check(model.openConversation(conversation) && model.rows().size() == 3,
          QStringLiteral("cached conversation was not projected"));
    check(model.rows().at(1).replyPreview == QStringLiteral("line one line two"),
          QStringLiteral("reply preview did not resolve current target text"));
    check(model.rows().at(2).canRetry && !model.rows().at(2).canReply,
          QStringLiteral("failed optimistic state actions are incorrect"));
    check(model.rows().first().reactions.size() == 6
              && model.rows().first().reactions.first().mine
              && model.rows().first().reactions.at(1).failed,
          QStringLiteral("reaction aggregates and failure state were not projected"));
    check(model.setReaction(target.messageId, V2LocalMessageRepository::ReactionKind::Like)
              && reactedMessage == target.messageId,
          QStringLiteral("reaction action did not preserve the message identity"));
    check(model.retryReaction(failedReaction.clientOperationId)
              && retriedReaction == failedReaction.clientOperationId,
          QStringLiteral("reaction retry did not preserve the operation identity"));
    check(model.chooseReply(target.messageId) && !model.replyBanner().isEmpty(),
          QStringLiteral("accepted target was not selectable"));
    check(model.sendReply(QStringLiteral("new reply"))
              && stagedTarget == target.messageId && stagedText == QStringLiteral("new reply")
              && model.replyTargetMessageId().isEmpty(),
          QStringLiteral("reply send did not preserve selected target or clear selection"));
    check(model.retry(failed.clientMessageId) && retried == failed.clientMessageId,
          QStringLiteral("failed row retry did not use stable client ID"));

    snapshot.messages[0].recalled = true;
    snapshot.messages[0].text.clear();
    check(model.refresh(), QStringLiteral("recalled projection refresh failed"));
    check(model.rows().at(0).text == QStringLiteral("此消息已被撤回")
              && !model.rows().at(0).canReply
              && model.rows().at(1).replyPreview == QStringLiteral("引用的消息已撤回"),
          QStringLiteral("recalled target state is not explicit"));
    snapshot.messages.removeFirst();
    check(model.refresh()
              && model.rows().first().replyPreview == QStringLiteral("引用的消息不可用"),
          QStringLiteral("deleted or absent target must render unavailable"));

    if (failures) return 1;
    qInfo() << "[V2WindowsMessagingViewModelTest] PASS";
    return 0;
}
