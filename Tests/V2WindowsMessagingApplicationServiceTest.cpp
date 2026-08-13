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
    const QString session1 = QStringLiteral("50000000-0000-4000-8000-000000000001");
    const QString session2 = QStringLiteral("50000000-0000-4000-8000-000000000002");
    V2LocalMessageRepository repository(directory.filePath(QStringLiteral("v2.sqlite")));
    check(repository.initialize(), repository.lastError().toStdString());
    const auto target = acceptedTarget(conversation, remote, remoteDevice);
    check(repository.mergeServerMessage(account, target, 7), repository.lastError().toStdString());

    QVector<QByteArray> sent;
    QList<QString> clientIds{
        QStringLiteral("client-reply-2"), QStringLiteral("client-reply-1")};
    V2WindowsMessagingApplicationService service(
        &repository, account, device,
        [&](const QByteArray &frame) { sent.append(frame); return true; },
        [] { return 1500; },
        [&] { return clientIds.takeLast(); });

    check(service.connectSession(session1), service.lastError().toStdString());
    V2LocalMessageRepository::Message optimistic;
    check(service.stageReply(conversation, target.messageId, QStringLiteral("reply one"),
                             &optimistic), service.lastError().toStdString());
    const auto submit = decode(sent.last());
    chat::v2::SubmitReplyMessage submitPayload;
    check(submit.message_type() == chat::v2::MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE
              && submit.client_message_id() == optimistic.clientMessageId.toStdString()
              && submitPayload.ParseFromString(submit.payload())
              && submitPayload.target_message_id() == target.messageId.toStdString(),
          "stage must persist then send exact type-105 reply identity");

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
              && snapshot.messages.last().reply.targetMessageId == target.messageId,
          "ACK must reconcile the same durable optimistic reply");

    service.disconnectSession();
    V2LocalMessageRepository::Message offline;
    const int beforeOffline = sent.size();
    check(service.stageReply(conversation, target.messageId, QStringLiteral("reply offline"),
                             &offline), service.lastError().toStdString());
    check(sent.size() == beforeOffline,
          "offline staging must not attempt transport dispatch");
    check(service.connectSession(session2), service.lastError().toStdString());
    const auto replay = decode(sent.last());
    check(replay.client_message_id() == offline.clientMessageId.toStdString(),
          "reconnect must replay the persisted client message identity");

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
    record->set_content("server reply");
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
              && snapshot.cursor == 10 && snapshot.messages.last().hasReply,
          "history page must atomically persist authoritative reply reference and cursor");

    if (failures) return 1;
    std::cout << "[V2WindowsMessagingApplicationServiceTest] PASS\n";
    return 0;
}
