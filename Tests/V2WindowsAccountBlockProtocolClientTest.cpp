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
std::string directoryPage(const std::string &requestId, const std::string &sessionId,
                          const std::string &targetId, const std::string &displayName,
                          bool hasMore, const std::string &cursor) {
    chat::v2::AccountBlockDirectoryPage payload;
    if (!targetId.empty()) {
        auto *row = payload.add_blocks();
        row->set_target_account_id(targetId);
        row->set_target_display_name(displayName);
        row->set_blocked_at_epoch_ms(901);
    }
    payload.set_has_more(hasMore);
    payload.set_next_after_target_account_id(cursor);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2); envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_ACCOUNT_BLOCK_DIRECTORY_PAGE);
    envelope.set_request_id(requestId); envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(902); envelope.set_payload(bytes(payload));
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
    throws([&] { client.listAccountBlocks(); },
           "directory before session must fail");
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
    const auto list = client.listAccountBlocks({}, 1);
    chat::v2::Envelope listEnvelope; chat::v2::ListAccountBlocks listPayload;
    check(listEnvelope.ParseFromString(list.bytes)
              && listPayload.ParseFromString(listEnvelope.payload())
              && listEnvelope.message_type() == chat::v2::MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS
              && listEnvelope.client_message_id().empty()
              && listPayload.after_target_account_id().empty()
              && listPayload.limit() == 1,
          "account block directory command lost its bounded cursor");
    const auto page = client.receive(directoryPage(
        list.requestId, session, target, "对方", true, target));
    check(page.type == V2WindowsAccountBlockProtocolClient::EventType::DirectoryPage
              && page.blocks.size() == 1
              && page.blocks.front().targetAccountId == target
              && page.blocks.front().targetDisplayName == "对方"
              && page.blocks.front().blockedAtEpochMs == 901
              && page.hasMore && page.nextAfterTargetAccountId == target,
          "valid account block directory page was not projected");
    const auto stale = client.listAccountBlocks(target, 1);
    throws([&] { client.receive(directoryPage(
        stale.requestId, session, target, "same", false, {})); },
        "directory row must advance beyond its requested cursor");
    check(client.pendingCount() == 1,
          "invalid directory response must retain retry correlation");
    client.abandon(stale.requestId);
    const auto retry = client.setAccountBlock(target, true, operation);
    check(retry.clientOperationId == operation && retry.requestId != command.requestId,
          "explicit retry must preserve operation identity only");
    client.clearSession();
    check(client.pendingCount() == 0, "disconnect must abandon pending operations");
    if (failures) return 1;
    std::cout << "[V2WindowsAccountBlockProtocolClientTest] PASS\n";
}
