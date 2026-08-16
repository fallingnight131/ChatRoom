#include "V2WindowsMessageSearchProtocolClient.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"
#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>
namespace {
int failures = 0;
void check(bool value, const char *message) { if (!value) { ++failures; std::cerr << message << '\n'; } }
template <typename F> void throws(F action, const char *message) { try { action(); check(false, message); } catch (const std::exception &) {} }
std::string bytes(const google::protobuf::MessageLite &value) {
    std::string out;
    if (!value.SerializeToString(&out)) throw std::runtime_error("test encode failed");
    return out;
}
std::string response(const std::string &requestId, const std::string &sessionId,
                     const chat::v2::ConversationMessageSearchPage &page) {
    chat::v2::Envelope envelope; envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE);
    envelope.set_request_id(requestId); envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(900); envelope.set_payload(bytes(page)); return bytes(envelope);
}
}
int main() {
    const std::string session = "40000000-0000-4000-8000-000000000001";
    const std::string conversation = "10000000-0000-4000-8000-000000000001";
    int next = 1;
    V2WindowsMessageSearchProtocolClient client([&] {
        return "30000000-0000-4000-8000-" + std::string(11, '0') + std::to_string(next++);
    }, [] { return 800; });
    throws([&] { client.search(conversation, "hello", 0, 20); }, "search before session must fail");
    client.bindSession(session);
    throws([&] { client.search(conversation, " hello", 0, 20); }, "unstripped query must fail");
    throws([&] { client.search(conversation, "\xe3\x80\x80hello", 0, 20); },
           "Unicode-unstripped query must fail");
    throws([&] { client.search(conversation, std::string(129, 'a'), 0, 20); }, "oversized query must fail");
    const auto command = client.search(conversation, "\xe8\x81\x8a\xe5\xa4\xa9", 0, 20);
    chat::v2::Envelope envelope; chat::v2::SearchConversationMessages query;
    check(envelope.ParseFromString(command.bytes) && query.ParseFromString(envelope.payload())
              && envelope.message_type() == chat::v2::MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES
              && query.conversation_id() == conversation && query.limit() == 20,
          "search command lost bounded fields");
    chat::v2::ConversationMessageSearchPage page; page.set_conversation_id(conversation);
    auto *hit = page.add_hits(); hit->set_conversation_id(conversation);
    hit->set_message_id("50000000-0000-4000-8000-000000000001"); hit->set_conversation_sequence(9);
    hit->set_sender_account_id("60000000-0000-4000-8000-000000000001");
    hit->set_sender_device_id("70000000-0000-4000-8000-000000000001");
    hit->set_client_message_id("80000000-0000-4000-8000-000000000001");
    hit->set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8); hit->set_content("chat");
    hit->set_accepted_at_epoch_ms(700); page.set_next_before_sequence(9); page.set_has_more(true);
    const auto event = client.receive(response(command.requestId, session, page));
    check(event.hits.size() == 1 && event.hits.front().conversationSequence == 9
              && event.nextBeforeSequence == 9 && event.hasMore && client.pendingCount() == 0,
          "valid search page was not correlated");
    const auto bad = client.search(conversation, "chat", 9, 20);
    throws([&] { client.receive(response(bad.requestId, session, page)); },
           "non-advancing search page must fail");
    client.clearSession(); check(client.pendingCount() == 0, "clear must abandon search");
    if (failures) return 1;
    std::cout << "[V2WindowsMessageSearchProtocolClientTest] PASS\n";
}
