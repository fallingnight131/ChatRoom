#include "V2WindowsConversationDirectoryProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/conversation.pb.h"
#include "chat/v2/envelope.pb.h"

#include <iostream>
#include <stdexcept>

namespace {
int failures = 0;
void check(bool condition, const char *message) {
    if (!condition) { ++failures; std::cerr << message << '\n'; }
}

template <typename Message>
std::string serialize(const Message &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}

std::string pageResponse(const std::string &requestId, const std::string &sessionId,
                         const chat::v2::ConversationDirectoryPage &page) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(1001);
    envelope.set_payload(serialize(page));
    return serialize(envelope);
}
}

int main() {
    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    int request = 0;
    V2WindowsConversationDirectoryProtocolClient client(
        [&] {
            ++request;
            return request == 1 ? "50000000-0000-4000-8000-000000000001"
                                : "50000000-0000-4000-8000-000000000002";
        }, [] { return 1000; });
    client.bindSession(sessionId);
    const auto command = client.list(2);
    chat::v2::Envelope envelope;
    chat::v2::ListConversations list;
    check(envelope.ParseFromString(command.bytes) && list.ParseFromString(envelope.payload())
              && envelope.message_type() == chat::v2::MESSAGE_TYPE_LIST_CONVERSATIONS
              && list.limit() == 2 && list.after_updated_at_epoch_ms() == 0,
          "first directory request must encode an empty composite cursor");

    chat::v2::ConversationDirectoryPage page;
    auto *first = page.add_conversations();
    first->set_conversation_id("60000000-0000-4000-8000-000000000002");
    first->set_kind(chat::v2::CONVERSATION_KIND_GROUP);
    first->set_display_name("Engineering");
    first->set_role(chat::v2::CONVERSATION_ROLE_ADMIN);
    first->set_latest_sequence(8);
    first->set_last_read_sequence(5);
    first->set_updated_at_epoch_ms(900);
    auto *second = page.add_conversations();
    second->set_conversation_id("60000000-0000-4000-8000-000000000001");
    second->set_kind(chat::v2::CONVERSATION_KIND_DIRECT);
    second->set_display_name("好友");
    second->set_role(chat::v2::CONVERSATION_ROLE_MEMBER);
    second->set_latest_sequence(3);
    second->set_last_read_sequence(3);
    second->set_updated_at_epoch_ms(900);
    page.set_next_updated_at_epoch_ms(900);
    page.set_next_conversation_id(second->conversation_id());
    page.set_has_more(true);
    const auto event = client.receive(pageResponse(command.requestId, sessionId, page));
    check(event.type == V2WindowsConversationDirectoryProtocolClient::EventType::Page
              && event.conversations.size() == 2 && event.hasMore
              && event.conversations.front().displayName == "Engineering"
              && event.next.conversationId == second->conversation_id(),
          "valid ordered page must expose user-facing records and exact next cursor");

    const auto next = client.list(100, event.next);
    chat::v2::Envelope nextEnvelope;
    chat::v2::ListConversations nextList;
    check(nextEnvelope.ParseFromString(next.bytes)
              && nextList.ParseFromString(nextEnvelope.payload())
              && nextList.after_updated_at_epoch_ms() == 900
              && nextList.after_conversation_id() == second->conversation_id(),
          "next request must preserve the composite descending cursor");
    auto unordered = page;
    unordered.mutable_conversations(1)->set_conversation_id(
        "60000000-0000-4000-8000-000000000003");
    bool rejected = false;
    try { client.receive(pageResponse(next.requestId, sessionId, unordered)); }
    catch (...) { rejected = true; }
    check(rejected && client.pendingCount() == 1,
          "unordered page must fail without consuming its legitimate correlation");
    client.clearSession();
    check(client.pendingCount() == 0,
          "disconnect must abandon volatile directory correlations");
    if (failures) return 1;
    std::cout << "[V2WindowsConversationDirectoryProtocolClientTest] PASS\n";
    return 0;
}
