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
    reply.forwarded = true;
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
    snapshot.messages[0].pinned = true;
    V2LocalMessageRepository::PinCommand failedPin;
    failedPin.conversationId = conversation;
    failedPin.messageId = target.messageId;
    failedPin.pinned = true;
    failedPin.clientOperationId = QStringLiteral("pin-failed-1");
    failedPin.state = V2LocalMessageRepository::DeliveryState::Failed;
    snapshot.pinCommands.append(failedPin);
    V2LocalMessageRepository::EditCommand conflictEdit{conversation, reply.messageId, 0,
        QStringLiteral("本地编辑草稿"),QStringLiteral("edit-conflict-1"),
        V2LocalMessageRepository::EditDeliveryState::Conflict,{}};
    snapshot.editCommands.append(conflictEdit);

    QString stagedTarget;
    QString stagedText;
    QString stagedPlainText;
    QString retried;
    QString reactedMessage;
    QString retriedReaction;
    QString pinnedMessage;
    QString retriedPin;
    QString editedMessage;
    QString editedText;
    QList<V2LocalMessageRepository::Mention> stagedMentions;
    QList<V2LocalMessageRepository::Mention> editedMentions;
    QString rebasedEdit;
    QString discardedEdit;
    QString forwardedSourceConversation;
    QString forwardedSourceMessage;
    QString forwardedTargetConversation;
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &text,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
            stagedPlainText = text;
            stagedMentions = mentions;
            return true;
        },
        [&](const QString &, const QString &targetId, const QString &text,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
            stagedTarget = targetId; stagedText = text; stagedMentions = mentions; return true;
        },
        [&](const QString &, const QString &clientId) { retried = clientId; return true; },
        [&](const QString &, const QString &messageId,
            V2LocalMessageRepository::ReactionKind) {
            reactedMessage = messageId; return true;
        },
        [&](const QString &, const QString &operationId) {
            retriedReaction = operationId; return true;
        },
        [&](const QString &, const QString &messageId) {
            pinnedMessage = messageId; return true;
        },
        [&](const QString &, const QString &operationId) {
            retriedPin = operationId; return true;
        },
        [&](const QString &, const QString &messageId, const QString &text,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
            editedMessage = messageId;
            editedText = text;
            editedMentions = mentions;
            return true;
        },
        [](const QString &, const QString &) { return true; },
        [&](const QString &, const QString &operationId) {
            rebasedEdit = operationId;
            return true;
        },
        [&](const QString &operationId) {
            discardedEdit = operationId;
            return true;
        });
    check(model.openConversation(conversation) && model.rows().size() == 3,
          QStringLiteral("cached conversation was not projected"));
    check(!model.rows().first().canForward,
          QStringLiteral("forwarding must remain default-off"));
    model.configureForwarding(
        [&](const QString &sourceConversationId, const QString &sourceMessageId,
            const QString &targetConversationId,
            V2LocalMessageRepository::Message *) {
            forwardedSourceConversation = sourceConversationId;
            forwardedSourceMessage = sourceMessageId;
            forwardedTargetConversation = targetConversationId;
            return true;
        });
    const QString forwardTarget =
        QStringLiteral("20000000-0000-4000-8000-000000000099");
    check(model.rows().first().canForward
              && !model.forwardMessage(target.messageId, conversation)
              && model.forwardMessage(target.messageId, forwardTarget)
              && forwardedSourceConversation == conversation
              && forwardedSourceMessage == target.messageId
              && forwardedTargetConversation == forwardTarget,
          QStringLiteral("forward action lost source, target, or default guard"));
    snapshot.messages[1].text = QStringLiteral("@张三 reply");
    snapshot.messages[1].mentions.append({target.senderAccountId, 0, 7});
    check(model.refresh() && model.rows().at(1).mentions.size() == 1,
          QStringLiteral("message row lost identity-preserving mention spans"));
    check(model.rows().at(1).replyPreview == QStringLiteral("line one line two"),
          QStringLiteral("reply preview did not resolve current target text"));
    check(model.rows().at(1).forwarded,
          QStringLiteral("forwarded presentation marker was not projected"));
    check(model.rows().at(2).canRetry && !model.rows().at(2).canReply,
          QStringLiteral("failed optimistic state actions are incorrect"));
    check(model.rows().first().reactions.size() == 6
              && model.rows().first().reactions.first().mine
              && model.rows().first().reactions.at(1).failed,
          QStringLiteral("reaction aggregates and failure state were not projected"));
    check(model.rows().first().pinned && model.rows().first().pinFailed
              && model.rows().first().pinOperationId == failedPin.clientOperationId,
          QStringLiteral("pin projection and failure state were not projected"));
    check(model.rows().at(1).editConflict
              && model.rows().at(1).text == conflictEdit.proposedText
              && !model.rows().at(1).canEdit,
          QStringLiteral("edit overlay and conflict state were not projected"));
    check(model.rebaseEdit(conflictEdit.clientOperationId)
              && rebasedEdit == conflictEdit.clientOperationId,
          QStringLiteral("explicit edit rebase lost the operation identity"));
    check(model.discardEdit(conflictEdit.clientOperationId)
              && discardedEdit == conflictEdit.clientOperationId,
          QStringLiteral("edit discard lost the operation identity"));
    snapshot.editCommands.clear();
    check(model.refresh() && model.rows().at(1).canEdit
              && model.editMessage(reply.messageId, QStringLiteral("@张三 新正文"),
                    {{target.senderAccountId, 0, 7}})
              && editedMessage == reply.messageId && editedText == QStringLiteral("@张三 新正文")
              && editedMentions.size() == 1,
          QStringLiteral("author edit action did not preserve message and content"));
    check(model.setReaction(target.messageId, V2LocalMessageRepository::ReactionKind::Like)
              && reactedMessage == target.messageId,
          QStringLiteral("reaction action did not preserve the message identity"));
    check(model.retryReaction(failedReaction.clientOperationId)
              && retriedReaction == failedReaction.clientOperationId,
          QStringLiteral("reaction retry did not preserve the operation identity"));
    check(model.setPin(target.messageId) && pinnedMessage == target.messageId,
          QStringLiteral("pin action did not preserve the message identity"));
    check(model.retryPin(failedPin.clientOperationId)
              && retriedPin == failedPin.clientOperationId,
          QStringLiteral("pin retry did not preserve the operation identity"));
    check(model.chooseReply(target.messageId) && !model.replyBanner().isEmpty(),
          QStringLiteral("accepted target was not selectable"));
    check(model.sendReply(QStringLiteral("@张三 new reply"),
              {{target.senderAccountId, 0, 7}})
              && stagedTarget == target.messageId && stagedText == QStringLiteral("@张三 new reply")
              && stagedMentions.size() == 1
              && model.replyTargetMessageId().isEmpty(),
          QStringLiteral("reply send did not preserve selected target or clear selection"));
    check(model.sendText(QStringLiteral("@张三 new message"),
              {{target.senderAccountId, 0, 7}})
              && stagedPlainText == QStringLiteral("@张三 new message")
              && stagedMentions.size() == 1,
          QStringLiteral("ordinary text send did not preserve content and mentions"));
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

    auto context = reply;
    context.messageId = QStringLiteral("30000000-0000-4000-8000-000000000099");
    context.clientMessageId = QStringLiteral("remote-context");
    context.senderAccountId = target.senderAccountId;
    context.conversationSequence = 99;
    context.text = QStringLiteral("仅用于搜索上下文");
    context.hasReply = false;
    check(model.applyTransientContext(conversation, {context})
              && std::any_of(model.rows().cbegin(), model.rows().cend(),
                    [&](const auto &row) { return row.messageId == context.messageId; })
              && snapshot.messages.size() == 2,
          QStringLiteral("transient context was not projected independently of durable data"));
    model.clearTransientContext();
    check(model.rows().size() == snapshot.messages.size(),
          QStringLiteral("transient context survived explicit session cleanup"));

    if (failures) return 1;
    qInfo() << "[V2WindowsMessagingViewModelTest] PASS";
    return 0;
}
