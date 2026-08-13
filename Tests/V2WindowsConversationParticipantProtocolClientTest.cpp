#include "V2WindowsConversationParticipantProtocolClient.h"

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
template <typename Message> std::string serialize(const Message &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}
std::string response(const std::string &requestId, const std::string &sessionId,
                     const chat::v2::ConversationParticipantPage &page) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(1001);
    envelope.set_payload(serialize(page));
    return serialize(envelope);
}
}

int main() {
    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    const std::string conversationId = "50000000-0000-4000-8000-000000000001";
    const std::string firstAccount = "60000000-0000-4000-8000-000000000001";
    const std::string secondAccount = "60000000-0000-4000-8000-000000000002";
    int request = 0;
    V2WindowsConversationParticipantProtocolClient client([&] {
        ++request;
        return request == 1 ? "70000000-0000-4000-8000-000000000001"
                            : "70000000-0000-4000-8000-000000000002";
    }, [] { return 1000; });
    client.bindSession(sessionId);
    const auto command = client.list(conversationId, 2);
    chat::v2::Envelope envelope;
    chat::v2::ListConversationParticipants query;
    check(envelope.ParseFromString(command.bytes) && query.ParseFromString(envelope.payload())
              && envelope.message_type()
                    == chat::v2::MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS
              && query.conversation_id() == conversationId && query.limit() == 2,
          "participant command must preserve conversation and bound");

    chat::v2::ConversationParticipantPage page;
    page.set_conversation_id(conversationId);
    auto *first = page.add_participants();
    first->set_account_id(firstAccount);
    first->set_display_name("Alice");
    first->set_role(chat::v2::CONVERSATION_ROLE_OWNER);
    auto *second = page.add_participants();
    second->set_account_id(secondAccount);
    second->set_display_name("李");
    second->set_role(chat::v2::CONVERSATION_ROLE_MEMBER);
    page.set_next_account_id(secondAccount);
    page.set_has_more(true);
    const auto event = client.receive(response(command.requestId, sessionId, page));
    check(event.type == V2WindowsConversationParticipantProtocolClient::EventType::Page
              && event.participants.size() == 2 && event.hasMore
              && event.participants.back().displayName == "李"
              && event.nextAccountId == secondAccount,
          "ordered Unicode participant page must expose exact cursor");

    const auto next = client.list(conversationId, 100, secondAccount);
    auto stale = page;
    stale.clear_participants();
    auto *duplicate = stale.add_participants();
    duplicate->set_account_id(secondAccount);
    duplicate->set_display_name("duplicate");
    duplicate->set_role(chat::v2::CONVERSATION_ROLE_MEMBER);
    stale.set_next_account_id(secondAccount);
    stale.set_has_more(false);
    bool rejected = false;
    try { client.receive(response(next.requestId, sessionId, stale)); }
    catch (...) { rejected = true; }
    check(rejected && client.pendingCount() == 1,
          "page must advance beyond its stable account cursor without consuming correlation");
    auto blankPage = page;
    blankPage.clear_participants();
    auto *blank = blankPage.add_participants();
    blank->set_account_id("60000000-0000-4000-8000-000000000003");
    blank->set_display_name("　");
    blank->set_role(chat::v2::CONVERSATION_ROLE_MEMBER);
    blankPage.set_next_account_id(blank->account_id());
    rejected = false;
    try { client.receive(response(next.requestId, sessionId, blankPage)); }
    catch (...) { rejected = true; }
    check(rejected && client.pendingCount() == 1,
          "Unicode-only whitespace display names must be rejected");
    client.abandon(next.requestId);
    check(client.pendingCount() == 0,
          "failed transport submission must abandon its participant correlation");
    rejected = false;
    try { client.receive(response(next.requestId, sessionId, page)); }
    catch (...) { rejected = true; }
    check(rejected, "an abandoned participant response must not be accepted later");
    client.clearSession();
    check(client.pendingCount() == 0, "disconnect must abandon participant correlations");
    if (failures) return 1;
    std::cout << "[V2WindowsConversationParticipantProtocolClientTest] PASS\n";
    return 0;
}
