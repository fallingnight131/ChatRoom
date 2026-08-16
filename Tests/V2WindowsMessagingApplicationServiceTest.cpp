#include "V2WindowsMessagingApplicationService.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"
#include <QCoreApplication>
#include <QTemporaryDir>
#include <QVector>
#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>

namespace {
int failures = 0;
void check(bool condition, const std::string &message) {
    if (!condition) { ++failures; std::cerr << message << '\n'; }
}
std::string bytes(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}
template <typename Payload>
QByteArray response(const chat::v2::Envelope &request, int type,
                    chat::v2::MessageKind kind, const Payload &payload) {
    chat::v2::Envelope result;
    result.set_protocol_version(2);
    result.set_kind(kind);
    result.set_message_type(type);
    result.set_request_id(request.request_id());
    result.set_session_id(request.session_id());
    result.set_client_message_id(request.client_message_id());
    result.set_sent_at_epoch_ms(2000);
    result.set_payload(bytes(payload));
    const auto encoded = bytes(result);
    return QByteArray(encoded.data(), static_cast<qsizetype>(encoded.size()));
}
chat::v2::Envelope decode(const QByteArray &frame) {
    chat::v2::Envelope result;
    if (!result.ParseFromArray(frame.constData(), static_cast<int>(frame.size())))
        throw std::runtime_error("decode failed");
    return result;
}
V2LocalMessageRepository::Message acceptedTarget(
        const QString &conversation, const QString &sender, const QString &device) {
    V2LocalMessageRepository::Message result;
    result.conversationId = conversation;
    result.messageId = QStringLiteral("40000000-0000-4000-8000-000000000001");
    result.conversationSequence = 7;
    result.senderAccountId = sender;
    result.senderDeviceId = device;
    result.clientMessageId = QStringLiteral("remote-target-client");
    result.text = QStringLiteral("target");
    result.acceptedAtEpochMs = 1000;
    result.createdAtEpochMs = 1000;
    result.state = V2LocalMessageRepository::DeliveryState::Accepted;
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    const QString account = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString device = QStringLiteral("20000000-0000-4000-8000-000000000001");
    const QString remote = QStringLiteral("10000000-0000-4000-8000-000000000002");
    const QString remoteDevice = QStringLiteral("20000000-0000-4000-8000-000000000002");
    const QString conversation = QStringLiteral("30000000-0000-4000-8000-000000000001");
    const QString forwardTarget = QStringLiteral("30000000-0000-4000-8000-000000000002");
    const QString session1 = QStringLiteral("50000000-0000-4000-8000-000000000001");
    const QString session2 = QStringLiteral("50000000-0000-4000-8000-000000000002");
    {
        V2LocalMessageRepository textRepository(
            directory.filePath(QStringLiteral("v2-text.sqlite")));
        check(textRepository.initialize(), textRepository.lastError().toStdString());
        QVector<QByteArray> textFrames;
        V2WindowsMessagingApplicationService textService(
            &textRepository, account, device,
            [&](const QByteArray &frame) { textFrames.append(frame); return true; },
            [] { return 1400; }, [] { return QStringLiteral("client-text-1"); });
        check(textService.saveDraft(conversation, QStringLiteral("restart draft"))
                  && textService.hydrate(conversation).draft
                        == QStringLiteral("restart draft"),
              "application service must persist conversation-scoped drafts");
        check(textService.connectSession(session1), textService.lastError().toStdString());
        V2LocalMessageRepository::Message optimisticText;
        const QList<V2LocalMessageRepository::Mention> textMentions{{remote, 0, 7}};
        check(textService.stageText(
                  conversation, QStringLiteral("@张三 ordinary text"),
                  &optimisticText, textMentions),
              textService.lastError().toStdString());
        const auto textCommand = decode(textFrames.last());
        chat::v2::SubmitMessage textPayload;
        const auto textSnapshot = textRepository.loadSnapshot(account, conversation);
        check(textCommand.message_type() == chat::v2::MESSAGE_TYPE_SUBMIT_MESSAGE
                  && textPayload.ParseFromString(textCommand.payload())
                  && textPayload.mentions_size() == 1
                  && !optimisticText.hasReply
                  && textSnapshot.messages.size() == 1
                  && textSnapshot.messages.first().state
                        == V2LocalMessageRepository::DeliveryState::Pending,
              "ordinary text must persist before type-103 dispatch with mentions");
    }
    V2LocalMessageRepository repository(directory.filePath(QStringLiteral("v2.sqlite")));
    check(repository.initialize(), repository.lastError().toStdString());
    const auto target = acceptedTarget(conversation, remote, remoteDevice);
    check(repository.mergeServerMessage(account, target, 7), repository.lastError().toStdString());

    V2WindowsMessagingApplicationService disabledForwarding(
        &repository, account, device, [](const QByteArray &) { return true; });
    V2LocalMessageRepository::Message disabledForward;
    check(!disabledForwarding.stageForward(
              conversation, target.messageId, forwardTarget, &disabledForward)
              && repository.loadSnapshot(account, forwardTarget).messages.isEmpty(),
          "default application service must reject forwarding before persistence");

    QVector<QByteArray> sent;
    QList<QString> clientIds{QStringLiteral("reaction-operation-spoof"),
        QStringLiteral("edit-operation-2"),
        QStringLiteral("edit-operation-1"),
        QStringLiteral("pin-operation-1"),
        QStringLiteral("reaction-operation-1"), QStringLiteral("client-reply-2"),
        QStringLiteral("forward-client-1"), QStringLiteral("client-reply-1")};
    V2WindowsMessagingApplicationService service(
        &repository, account, device,
        [&](const QByteArray &frame) { sent.append(frame); return true; },
        [] { return 1500; },
        [&] { return clientIds.takeLast(); }, true);

    check(service.connectSession(session1), service.lastError().toStdString());
    V2LocalMessageRepository::Message optimistic;
    const QList<V2LocalMessageRepository::Mention> mentions{{remote, 0, 7}};
    check(service.stageReply(conversation, target.messageId, QStringLiteral("@张三 reply one"),
                             &optimistic, mentions), service.lastError().toStdString());
    const auto submit = decode(sent.last());
    chat::v2::SubmitReplyMessage submitPayload;
    check(submit.message_type() == chat::v2::MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE
              && submit.client_message_id() == optimistic.clientMessageId.toStdString()
              && submitPayload.ParseFromString(submit.payload())
              && submitPayload.target_message_id() == target.messageId.toStdString()
              && submitPayload.mentions_size() == 1
              && submitPayload.mentions(0).target_account_id() == remote.toStdString(),
          "stage must persist then send exact type-105 reply and mention identity");

    chat::v2::MessageAccepted accepted;
    accepted.set_conversation_id(conversation.toStdString());
    accepted.set_message_id("60000000-0000-4000-8000-000000000001");
    accepted.set_conversation_sequence(8);
    accepted.set_accepted_at_epoch_ms(1600);
    const auto acceptedOutcome = service.receiveFrame(response(
        submit, chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED,
        chat::v2::MESSAGE_KIND_RESPONSE, accepted));
    auto snapshot = service.hydrate(conversation);
    check(acceptedOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::Accepted
              && snapshot.messages.size() == 2
              && snapshot.messages.last().state
                 == V2LocalMessageRepository::DeliveryState::Accepted
              && snapshot.messages.last().reply.targetMessageId == target.messageId
              && snapshot.messages.last().mentions.size() == 1,
          "ACK must reconcile the same durable optimistic reply");

    V2LocalMessageRepository::Message optimisticForward;
    check(service.stageForward(conversation, target.messageId, forwardTarget,
                               &optimisticForward), service.lastError().toStdString());
    const auto forwardRequest = decode(sent.last());
    chat::v2::ForwardMessage forwardPayload;
    check(forwardRequest.message_type() == chat::v2::MESSAGE_TYPE_FORWARD_MESSAGE
              && forwardRequest.client_message_id() == "forward-client-1"
              && forwardPayload.ParseFromString(forwardRequest.payload())
              && forwardPayload.source_message_id() == target.messageId.toStdString()
              && repository.loadSnapshot(account, forwardTarget).messages.first()
                    .forwardSourceConversationId == conversation,
          "forward must persist exact local source authority before type-119 dispatch");
    chat::v2::MessageAccepted forwardAccepted;
    forwardAccepted.set_conversation_id(forwardTarget.toStdString());
    forwardAccepted.set_message_id("60000000-0000-4000-8000-000000000011");
    forwardAccepted.set_conversation_sequence(1); forwardAccepted.set_accepted_at_epoch_ms(1601);
    check(service.receiveFrame(response(forwardRequest, chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED,
              chat::v2::MESSAGE_KIND_RESPONSE, forwardAccepted)).type
              == V2WindowsMessagingApplicationService::OutcomeType::Accepted
              && repository.loadSnapshot(account, forwardTarget).messages.first()
                    .forwardSourceConversationId.isEmpty(),
          "forward ACK must clear private local source authority");

    service.disconnectSession();
    V2LocalMessageRepository::Message offline;
    const int beforeOffline = sent.size();
    check(service.stageReply(conversation, target.messageId, QStringLiteral("@张三 reply offline"),
                             &offline, mentions), service.lastError().toStdString());
    check(sent.size() == beforeOffline,
          "offline staging must not attempt transport dispatch");
    check(service.connectSession(session2), service.lastError().toStdString());
    const auto replay = decode(sent.last());
    chat::v2::SubmitReplyMessage replayPayload;
    check(replay.client_message_id() == offline.clientMessageId.toStdString()
              && replayPayload.ParseFromString(replay.payload())
              && replayPayload.mentions_size() == 1,
          "reconnect must replay the persisted client message and mention identity");

    chat::v2::ProtocolError busy;
    busy.set_code(chat::v2::PROTOCOL_ERROR_CODE_RATE_LIMITED);
    busy.set_safe_message("busy");
    busy.set_retryable(true);
    const auto deferred = service.receiveFrame(response(
        replay, chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR,
        chat::v2::MESSAGE_KIND_ERROR, busy));
    check(deferred.type == V2WindowsMessagingApplicationService::OutcomeType::Deferred
              && repository.pendingSends(account).size() == 1,
          "retryable denial must preserve pending intent without a hot loop");
    const int beforeReconnect = sent.size();
    service.disconnectSession();
    check(service.connectSession(session2), service.lastError().toStdString());
    check(sent.size() == beforeReconnect + 1
              && decode(sent.last()).client_message_id() == offline.clientMessageId.toStdString(),
          "fresh reconnect must retry deferred intent with the same identity");

    const auto replayAfterReconnect = decode(sent.last());
    chat::v2::ProtocolError denied;
    denied.set_code(chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED);
    denied.set_safe_message("not authorized");
    const auto failed = service.receiveFrame(response(
        replayAfterReconnect, chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR,
        chat::v2::MESSAGE_KIND_ERROR, denied));
    check(failed.type == V2WindowsMessagingApplicationService::OutcomeType::SendFailed
              && repository.pendingSends(account).isEmpty(),
          "permanent denial must require explicit retry");
    check(service.retry(conversation, offline.clientMessageId), service.lastError().toStdString());
    check(decode(sent.last()).client_message_id() == offline.clientMessageId.toStdString(),
          "explicit retry must keep the original idempotency key");

    chat::v2::MessageAccepted secondAccepted = accepted;
    secondAccepted.set_message_id("60000000-0000-4000-8000-000000000002");
    secondAccepted.set_conversation_sequence(9);
    const auto retryFrame = decode(sent.last());
    service.receiveFrame(response(retryFrame, chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED,
                                  chat::v2::MESSAGE_KIND_RESPONSE, secondAccepted));

    check(service.requestHistory(conversation), service.lastError().toStdString());
    const auto historyRequest = decode(sent.last());
    chat::v2::ReadMessageHistory historyPayload;
    check(historyPayload.ParseFromString(historyRequest.payload())
              && historyPayload.after_sequence() == 9,
          "history repair must start at the durable cursor");
    chat::v2::MessageHistoryPage page;
    page.set_conversation_id(conversation.toStdString());
    auto *record = page.add_messages();
    record->set_conversation_id(conversation.toStdString());
    record->set_message_id("60000000-0000-4000-8000-000000000003");
    record->set_conversation_sequence(10);
    record->set_sender_account_id(remote.toStdString());
    record->set_sender_device_id(remoteDevice.toStdString());
    record->set_client_message_id("remote-reply");
    record->set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    record->set_content(u8"@李四 server reply");
    auto *recordMention = record->add_mentions();
    recordMention->set_target_account_id(account.toStdString());
    recordMention->set_start_utf8_byte(0);
    recordMention->set_length_utf8_bytes(7);
    record->set_accepted_at_epoch_ms(1800);
    auto *reference = record->mutable_reply();
    reference->set_target_message_id(target.messageId.toStdString());
    reference->set_target_conversation_sequence(7);
    reference->set_target_sender_account_id(remote.toStdString());
    page.set_next_sequence(10);
    page.set_latest_sequence(10);
    const auto historyOutcome = service.receiveFrame(response(
        historyRequest, chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
        chat::v2::MESSAGE_KIND_RESPONSE, page));
    snapshot = service.hydrate(conversation);
    check(historyOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::HistoryApplied
              && snapshot.cursor == 10 && snapshot.messages.last().hasReply
              && snapshot.messages.last().mentions.size() == 1,
          "history page must atomically persist authoritative reply reference and cursor");

    check(service.requestHistory(conversation), service.lastError().toStdString());
    const auto mutationRequest = decode(sent.last());
    chat::v2::MessageHistoryPage mutationPage;
    mutationPage.set_conversation_id(conversation.toStdString());
    auto *mutation = mutationPage.add_entries();
    mutation->set_conversation_id(conversation.toStdString());
    mutation->set_conversation_sequence(11);
    auto *recall = mutation->mutable_recall();
    recall->set_conversation_id(conversation.toStdString());
    recall->set_conversation_sequence(11);
    recall->set_message_id(target.messageId.toStdString());
    recall->set_actor_account_id(remote.toStdString());
    recall->set_source("V2");
    recall->set_occurred_at_epoch_ms(1900);
    mutationPage.set_next_sequence(11);
    mutationPage.set_latest_sequence(11);
    const auto mutationOutcome = service.receiveFrame(response(
        mutationRequest, chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
        chat::v2::MESSAGE_KIND_RESPONSE, mutationPage));
    snapshot = service.hydrate(conversation);
    const auto recalledTarget = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &message) { return message.messageId == target.messageId; });
    check(mutationOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::HistoryApplied
              && recalledTarget != snapshot.messages.cend() && recalledTarget->recalled
              && recalledTarget->text.isEmpty(),
          "ordered recall must erase cached target content and make it unavailable");
    check(service.setReaction(conversation,
              QStringLiteral("60000000-0000-4000-8000-000000000002"),
              V2LocalMessageRepository::ReactionKind::Love),
          service.lastError().toStdString());
    const auto reactionRequest = decode(sent.last());
    chat::v2::MessageReactionApplied appliedReaction;
    appliedReaction.set_conversation_id(conversation.toStdString());
    appliedReaction.set_message_id("60000000-0000-4000-8000-000000000002");
    appliedReaction.set_reaction(chat::v2::MESSAGE_REACTION_KIND_LOVE);
    appliedReaction.set_active(true); appliedReaction.set_actor_account_id(account.toStdString());
    appliedReaction.set_client_operation_id("reaction-operation-1");
    appliedReaction.set_changed(false); appliedReaction.set_conversation_sequence(0);
    appliedReaction.set_occurred_at_epoch_ms(1950);
    const auto reactionOutcome = service.receiveFrame(response(reactionRequest,
        chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_APPLIED,
        chat::v2::MESSAGE_KIND_RESPONSE, appliedReaction));
    const auto reactionSnapshot = service.hydrate(conversation);
    const auto reactedMessage = std::find_if(reactionSnapshot.messages.cbegin(),
        reactionSnapshot.messages.cend(), [](const auto &message) {
            return message.messageId
                == QStringLiteral("60000000-0000-4000-8000-000000000002");
        });
    check(reactionOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::ReactionApplied
              && reactionSnapshot.reactionCommands.isEmpty()
              && reactedMessage != reactionSnapshot.messages.cend()
              && reactedMessage->reactions.first().actorAccountIds.contains(account),
          "reaction ACK must converge the durable optimistic operation");
    const QString pinTarget = QStringLiteral("60000000-0000-4000-8000-000000000002");
    check(service.setPin(conversation, pinTarget), service.lastError().toStdString());
    const auto pinRequest = decode(sent.last());
    auto optimisticPin = service.hydrate(conversation);
    const auto optimisticPinnedMessage = std::find_if(
        optimisticPin.messages.cbegin(), optimisticPin.messages.cend(),
        [&](const auto &message) { return message.messageId == pinTarget; });
    check(optimisticPinnedMessage != optimisticPin.messages.cend()
              && optimisticPinnedMessage->pinned && optimisticPin.pinCommands.size() == 1,
          "pin action must atomically project state and persist its outbox identity");
    chat::v2::MessagePinApplied appliedPin;
    appliedPin.set_conversation_id(conversation.toStdString());
    appliedPin.set_message_id(pinTarget.toStdString());
    appliedPin.set_pinned(true);
    appliedPin.set_actor_account_id(account.toStdString());
    appliedPin.set_client_operation_id("pin-operation-1");
    appliedPin.set_changed(false);
    appliedPin.set_conversation_sequence(0);
    appliedPin.set_occurred_at_epoch_ms(1960);
    const auto pinOutcome = service.receiveFrame(response(pinRequest,
        chat::v2::MESSAGE_TYPE_MESSAGE_PIN_APPLIED,
        chat::v2::MESSAGE_KIND_RESPONSE, appliedPin));
    const auto afterPinAck = service.hydrate(conversation);
    check(pinOutcome.type == V2WindowsMessagingApplicationService::OutcomeType::PinApplied
              && afterPinAck.pinCommands.isEmpty() && afterPinAck.cursor == 11,
          "pin ACK must clear the durable command without advancing the cursor");
    chat::v2::MessagePinChangedRecord pinChanged;
    pinChanged.set_conversation_id(conversation.toStdString());
    pinChanged.set_conversation_sequence(12);
    pinChanged.set_message_id(pinTarget.toStdString());
    pinChanged.set_pinned(false);
    pinChanged.set_actor_account_id(remote.toStdString());
    pinChanged.set_client_operation_id("pin-operation-remote");
    pinChanged.set_occurred_at_epoch_ms(1970);
    chat::v2::Envelope eventContext;
    eventContext.set_session_id(session2.toStdString());
    const auto livePinOutcome = service.receiveFrame(response(eventContext,
        chat::v2::MESSAGE_TYPE_MESSAGE_PIN_CHANGED,
        chat::v2::MESSAGE_KIND_EVENT, pinChanged));
    const auto afterLivePin = service.hydrate(conversation);
    const auto livePinnedMessage = std::find_if(
        afterLivePin.messages.cbegin(), afterLivePin.messages.cend(),
        [&](const auto &message) { return message.messageId == pinTarget; });
    check(livePinOutcome.type == V2WindowsMessagingApplicationService::OutcomeType::PinChanged
              && afterLivePin.cursor == 11
              && livePinnedMessage != afterLivePin.messages.cend()
              && !livePinnedMessage->pinned,
          "live pin event must project state without skipping durable history");
    const auto pinRepairRequest = decode(sent.last());
    chat::v2::ReadMessageHistory pinRepairPayload;
    check(pinRepairPayload.ParseFromString(pinRepairRequest.payload())
              && pinRepairPayload.after_sequence() == 11,
          "live pin event must repair history from the durable cursor");
    chat::v2::MessageHistoryPage pinRepairPage;
    pinRepairPage.set_conversation_id(conversation.toStdString());
    auto *pinEntry = pinRepairPage.add_entries();
    pinEntry->set_conversation_id(conversation.toStdString());
    pinEntry->set_conversation_sequence(12);
    *pinEntry->mutable_pin() = pinChanged;
    pinRepairPage.set_next_sequence(12);
    pinRepairPage.set_latest_sequence(12);
    const auto pinRepairOutcome = service.receiveFrame(response(pinRepairRequest,
        chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
        chat::v2::MESSAGE_KIND_RESPONSE, pinRepairPage));
    check(pinRepairOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::HistoryApplied
              && service.hydrate(conversation).cursor == 12,
          "pin history repair must authoritatively advance the durable cursor");
    check(service.editMessage(conversation, pinTarget, QStringLiteral("@张三 我的 Windows 编辑"),
                              mentions),
          service.lastError().toStdString());
    const auto editRequest = decode(sent.last());
    chat::v2::EditMessage editPayload;
    check(editPayload.ParseFromString(editRequest.payload())
              && editPayload.expected_revision() == 0
              && editPayload.client_operation_id() == "edit-operation-1"
              && editPayload.mentions_size() == 1,
          "edit must persist and send one revision-safe operation");
    chat::v2::ProtocolError conflict;
    conflict.set_code(chat::v2::PROTOCOL_ERROR_CODE_MESSAGE_REVISION_CONFLICT);
    conflict.set_safe_message("revision conflict");
    const auto conflictOutcome = service.receiveFrame(response(editRequest,
        chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR, chat::v2::MESSAGE_KIND_ERROR, conflict));
    auto conflicted = service.hydrate(conversation);
    check(conflictOutcome.type == V2WindowsMessagingApplicationService::OutcomeType::EditConflict
              && conflicted.editCommands.size() == 1
              && conflicted.editCommands.first().proposedText == QStringLiteral("@张三 我的 Windows 编辑")
              && conflicted.editCommands.first().mentions.size() == 1,
          "revision conflict must preserve the local edit overlay");
    const auto editRepairRequest = decode(sent.last());
    chat::v2::MessageHistoryPage editRepairPage;
    editRepairPage.set_conversation_id(conversation.toStdString());
    auto *editEntry = editRepairPage.add_entries();
    editEntry->set_conversation_id(conversation.toStdString());
    editEntry->set_conversation_sequence(13);
    auto *remoteEdit = editEntry->mutable_edit();
    remoteEdit->set_conversation_id(conversation.toStdString());
    remoteEdit->set_conversation_sequence(13);
    remoteEdit->set_message_id(pinTarget.toStdString());
    remoteEdit->set_content_revision(1);
    remoteEdit->set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    remoteEdit->set_content("other device");
    remoteEdit->set_actor_account_id(account.toStdString());
    remoteEdit->set_client_operation_id("edit-operation-remote");
    remoteEdit->set_occurred_at_epoch_ms(1980);
    editRepairPage.set_next_sequence(13); editRepairPage.set_latest_sequence(13);
    service.receiveFrame(response(editRepairRequest, chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
                                  chat::v2::MESSAGE_KIND_RESPONSE, editRepairPage));
    check(service.rebaseEdit(conversation, QStringLiteral("edit-operation-1")),
          service.lastError().toStdString());
    const auto rebasedRequest = decode(sent.last());
    check(editPayload.ParseFromString(rebasedRequest.payload())
              && editPayload.expected_revision() == 1
              && editPayload.client_operation_id() == "edit-operation-2"
              && editPayload.content() == u8"@张三 我的 Windows 编辑"
              && editPayload.mentions_size() == 1,
          "explicit rebase must rotate operation id and retain proposed text and mentions");
    chat::v2::MessageEditApplied editApplied;
    editApplied.set_conversation_id(conversation.toStdString());
    editApplied.set_message_id(pinTarget.toStdString());
    editApplied.set_content_revision(2);
    editApplied.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    editApplied.set_content(u8"@张三 我的 Windows 编辑");
    auto *editMention = editApplied.add_mentions();
    editMention->set_target_account_id(remote.toStdString());
    editMention->set_start_utf8_byte(0);
    editMention->set_length_utf8_bytes(7);
    editApplied.set_actor_account_id(account.toStdString());
    editApplied.set_client_operation_id("edit-operation-2");
    editApplied.set_changed(true); editApplied.set_conversation_sequence(14);
    editApplied.set_occurred_at_epoch_ms(1990);
    const auto editOutcome = service.receiveFrame(response(rebasedRequest,
        chat::v2::MESSAGE_TYPE_MESSAGE_EDIT_APPLIED, chat::v2::MESSAGE_KIND_RESPONSE, editApplied));
    const auto afterEditAck = service.hydrate(conversation);
    const auto editedMessage = std::find_if(afterEditAck.messages.cbegin(),afterEditAck.messages.cend(),
        [&](const auto &message){ return message.messageId==pinTarget; });
    check(editOutcome.type == V2WindowsMessagingApplicationService::OutcomeType::EditApplied
              && afterEditAck.editCommands.isEmpty() && afterEditAck.cursor == 13
              && editedMessage != afterEditAck.messages.cend()
              && editedMessage->text == QStringLiteral("@张三 我的 Windows 编辑")
              && editedMessage->mentions.size() == 1
              && editedMessage->contentRevision == 2,
          "edit ACK must converge content without advancing history cursor");
    V2LocalMessageRepository::Message rejectedReply;
    check(!service.stageReply(conversation, target.messageId, QStringLiteral("too late"),
                              &rejectedReply),
          "recalled target must not remain replyable");

    check(service.setReaction(conversation,
              QStringLiteral("60000000-0000-4000-8000-000000000002"),
              V2LocalMessageRepository::ReactionKind::Love),
          service.lastError().toStdString());
    const auto spoofRequest = decode(sent.last());
    auto spoofedReaction = appliedReaction;
    spoofedReaction.set_active(false);
    spoofedReaction.set_actor_account_id(remote.toStdString());
    spoofedReaction.set_client_operation_id("reaction-operation-spoof");
    const auto spoofOutcome = service.receiveFrame(response(spoofRequest,
        chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_APPLIED,
        chat::v2::MESSAGE_KIND_RESPONSE, spoofedReaction));
    check(spoofOutcome.type
              == V2WindowsMessagingApplicationService::OutcomeType::ProtocolFailure
              && !service.connected(),
          "reaction ACK for a different actor must fail closed");

    if (failures) return 1;
    std::cout << "[V2WindowsMessagingApplicationServiceTest] PASS\n";
    return 0;
}
