#include "V2WindowsAccountBlockProtocolClient.h"
#include "chat/v2/contact.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>

namespace {
int failures = 0;
void check(bool value, const char *message) {
    if (!value) { ++failures; std::cerr << message << '\n'; }
}
template <typename F> void throws(F action, const char *message) {
    try { action(); check(false, message); } catch (const std::exception &) {}
}
std::string bytes(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("test encode failed");
    return result;
}
std::string applied(const std::string &requestId, const std::string &sessionId,
                    const std::string &operationId, const std::string &actorId,
                    const std::string &targetId, bool blocked, bool changed) {
    chat::v2::AccountBlockApplied payload;
    payload.set_actor_account_id(actorId); payload.set_target_account_id(targetId);
    payload.set_blocked(blocked); payload.set_changed(changed);
    payload.set_client_operation_id(operationId);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2); envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED);
    envelope.set_request_id(requestId); envelope.set_session_id(sessionId);
    envelope.set_client_message_id(operationId); envelope.set_sent_at_epoch_ms(900);
    envelope.set_payload(bytes(payload));
    return bytes(envelope);
}
}

int main() {
    const std::string session = "40000000-0000-4000-8000-000000000001";
    const std::string actor = "50000000-0000-4000-8000-000000000001";
    const std::string target = "60000000-0000-4000-8000-000000000001";
    const std::string operation = "70000000-0000-4000-8000-000000000001";
    int next = 1;
    V2WindowsAccountBlockProtocolClient client([&] {
        return "30000000-0000-4000-8000-" + std::string(11, '0') + std::to_string(next++);
    }, [] { return 800; });
    throws([&] { client.setAccountBlock(target, true, operation); },
           "mutation before session must fail");
    client.bindSession(session, actor);
    throws([&] { client.setAccountBlock(actor, true, operation); },
           "self block must fail before transport");
    const auto command = client.setAccountBlock(target, true, operation);
    chat::v2::Envelope envelope; chat::v2::SetAccountBlock payload;
    check(envelope.ParseFromString(command.bytes) && payload.ParseFromString(envelope.payload())
              && envelope.message_type() == chat::v2::MESSAGE_TYPE_SET_ACCOUNT_BLOCK
              && envelope.client_message_id() == operation
              && payload.target_account_id() == target && payload.blocked()
              && payload.client_operation_id() == operation,
          "account block command lost correlated desired state");
    throws([&] { client.receive(applied(command.requestId, session, operation,
                                       target, target, true, true)); },
           "response actor substitution must fail closed");
    check(client.pendingCount() == 1, "invalid response must retain retry correlation");
    const auto event = client.receive(applied(command.requestId, session, operation,
                                              actor, target, true, true));
    check(event.type == V2WindowsAccountBlockProtocolClient::EventType::Applied
              && event.actorAccountId == actor && event.targetAccountId == target
              && event.blocked && event.changed && event.clientOperationId == operation
              && client.pendingCount() == 0,
          "valid account block result was not strictly correlated");
    const auto retry = client.setAccountBlock(target, true, operation);
    check(retry.clientOperationId == operation && retry.requestId != command.requestId,
          "explicit retry must preserve operation identity only");
    client.clearSession();
    check(client.pendingCount() == 0, "disconnect must abandon pending operations");
    if (failures) return 1;
    std::cout << "[V2WindowsAccountBlockProtocolClientTest] PASS\n";
}
