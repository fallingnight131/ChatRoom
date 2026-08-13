#include "V2WindowsMessagingProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"
#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
int failures = 0;
void check(bool condition, const std::string &message) {
    if (!condition) { ++failures; std::cerr << message << '\n'; }
}
template <typename Function>
void checkThrows(Function action, const std::string &message) {
    try { action(); check(false, message); } catch (const std::exception &) {}
}
std::string serialize(const google::protobuf::MessageLite &message) {
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("encode failed");
    return encoded;
}
template <typename Payload>
std::string envelope(int type, chat::v2::MessageKind kind, const std::string &requestId,
                     const std::string &sessionId, const std::string &clientMessageId,
                     const Payload &payload) {
    chat::v2::Envelope result;
    result.set_protocol_version(2);
    result.set_kind(kind);
    result.set_message_type(type);
    result.set_request_id(requestId);
    result.set_session_id(sessionId);
    result.set_client_message_id(clientMessageId);
    result.set_sent_at_epoch_ms(900);
    result.set_payload(serialize(payload));
    return serialize(result);
}
chat::v2::MessageRecord record(const std::string &conversationId,
                               const std::string &messageId,
                               std::uint64_t sequence) {
    chat::v2::MessageRecord result;
    result.set_conversation_id(conversationId);
    result.set_message_id(messageId);
    result.set_conversation_sequence(sequence);
    result.set_sender_account_id("30000000-0000-4000-8000-000000000001");
    result.set_sender_device_id("40000000-0000-4000-8000-000000000001");
    result.set_client_message_id("local-1");
    result.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    result.set_content("hello");
    result.set_accepted_at_epoch_ms(800);
    return result;
}
}

int main() {
    const std::string sessionId = "10000000-0000-4000-8000-000000000001";
    const std::string conversationId = "20000000-0000-4000-8000-000000000001";
    const std::string targetId = "50000000-0000-4000-8000-000000000001";
    const std::string replyId = "50000000-0000-4000-8000-000000000002";
    const std::string mentionedAccountId = "60000000-0000-4000-8000-000000000001";
    std::vector<std::string> ids{
        "request-8",
        "request-7", "request-6", "request-5", "request-4", "request-3",
        "request-2", "request-1"};
    V2WindowsMessagingProtocolClient client(
        [&] { auto value = ids.back(); ids.pop_back(); return value; }, [] { return 700; });

    checkThrows([&] { client.submitText(conversationId, "local-1", "hello"); },
                "unauthenticated submission must fail");
    client.bindSession(sessionId);
    checkThrows([&] { client.submitText(conversationId, "local-1", std::string("\xc0\x80", 2)); },
                "invalid UTF-8 must fail before serialization");
    checkThrows([&] {
        client.submitText(conversationId, "local-invalid", u8"@张三",
            {{mentionedAccountId, 0, 2}});
    }, "mention spans that split a Unicode scalar must fail before serialization");

    const std::string replyText = u8"@张三 reply";
    const std::vector<V2WindowsMessagingProtocolClient::Mention> replyMentions{
        {mentionedAccountId, 0, 7}};
    const auto reply = client.submitReplyText(
        conversationId, "local-reply", targetId, replyText, replyMentions);
    chat::v2::Envelope replyEnvelope;
    chat::v2::SubmitReplyMessage replyPayload;
    check(replyEnvelope.ParseFromString(reply.bytes)
              && replyEnvelope.message_type() == chat::v2::MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE
              && replyEnvelope.kind() == chat::v2::MESSAGE_KIND_COMMAND
              && replyEnvelope.session_id() == sessionId
              && replyEnvelope.client_message_id() == "local-reply"
              && replyPayload.ParseFromString(replyEnvelope.payload())
              && replyPayload.conversation_id() == conversationId
              && replyPayload.target_message_id() == targetId
              && replyPayload.content() == replyText
              && replyPayload.mentions_size() == 1
              && replyPayload.mentions(0).target_account_id() == mentionedAccountId
              && replyPayload.mentions(0).length_utf8_bytes() == 7,
          "reply command must preserve distinct type, stable identities, and UTF-8 mentions");

    chat::v2::MessageAccepted accepted;
    accepted.set_conversation_id(conversationId);
    accepted.set_message_id(replyId);
    accepted.set_conversation_sequence(8);
    accepted.set_accepted_at_epoch_ms(850);
    const auto ack = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED, chat::v2::MESSAGE_KIND_RESPONSE,
        reply.requestId, sessionId, reply.clientMessageId, accepted));
    check(ack.type == V2WindowsMessagingProtocolClient::EventType::Accepted
              && ack.clientMessageId == "local-reply" && ack.messageId == replyId
              && ack.conversationSequence == 8 && client.pendingCount() == 0,
          "acceptance must reconcile the exact optimistic identity");

    const auto history = client.readHistory(conversationId, 0, 100);
    chat::v2::MessageHistoryPage page;
    page.set_conversation_id(conversationId);
    auto first = record(conversationId, targetId, 7);
    *page.add_messages() = first;
    auto second = record(conversationId, replyId, 8);
    second.set_client_message_id("local-reply");
    second.set_content(replyText);
    auto *historyMention = second.add_mentions();
    historyMention->set_target_account_id(mentionedAccountId);
    historyMention->set_start_utf8_byte(0);
    historyMention->set_length_utf8_bytes(7);
    auto *reference = second.mutable_reply();
    reference->set_target_message_id(targetId);
    reference->set_target_conversation_sequence(7);
    reference->set_target_sender_account_id(first.sender_account_id());
    *page.add_messages() = second;
    page.set_next_sequence(8);
    page.set_latest_sequence(8);
    const auto historyEvent = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE, chat::v2::MESSAGE_KIND_RESPONSE,
        history.requestId, sessionId, {}, page));
    check(historyEvent.type == V2WindowsMessagingProtocolClient::EventType::HistoryPage
              && historyEvent.messages.size() == 2
              && historyEvent.messages.at(1).hasReply
              && historyEvent.messages.at(1).reply.targetMessageId == targetId
              && historyEvent.messages.at(1).mentions.size() == 1
              && historyEvent.messages.at(1).mentions.front().targetAccountId
                    == mentionedAccountId
              && historyEvent.nextSequence == 8,
          "history must expose validated authoritative reply and mention identity");

    const auto mutationHistory = client.readHistory(conversationId, 8, 10);
    chat::v2::MessageHistoryPage mutationPage;
    mutationPage.set_conversation_id(conversationId);
    auto *entry = mutationPage.add_entries();
    entry->set_conversation_id(conversationId);
    entry->set_conversation_sequence(9);
    auto *recall = entry->mutable_recall();
    recall->set_conversation_id(conversationId);
    recall->set_conversation_sequence(9);
    recall->set_message_id(targetId);
    recall->set_actor_account_id("30000000-0000-4000-8000-000000000001");
    recall->set_source("V2");
    recall->set_occurred_at_epoch_ms(860);
    auto *reactionEntry = mutationPage.add_entries();
    reactionEntry->set_conversation_id(conversationId);
    reactionEntry->set_conversation_sequence(10);
    auto *reaction = reactionEntry->mutable_reaction();
    reaction->set_conversation_id(conversationId);
    reaction->set_conversation_sequence(10);
    reaction->set_message_id(replyId);
    reaction->set_reaction(chat::v2::MESSAGE_REACTION_KIND_LOVE);
    reaction->set_active(true);
    reaction->set_actor_account_id("30000000-0000-4000-8000-000000000001");
    reaction->set_client_operation_id("reaction-history-1");
    reaction->set_occurred_at_epoch_ms(870);
    auto *pinEntry = mutationPage.add_entries();
    pinEntry->set_conversation_id(conversationId);
    pinEntry->set_conversation_sequence(11);
    auto *pinHistory = pinEntry->mutable_pin();
    pinHistory->set_conversation_id(conversationId);
    pinHistory->set_conversation_sequence(11);
    pinHistory->set_message_id(replyId);
    pinHistory->set_pinned(true);
    pinHistory->set_actor_account_id("30000000-0000-4000-8000-000000000001");
    pinHistory->set_client_operation_id("pin-history-1");
    pinHistory->set_occurred_at_epoch_ms(875);
    auto *editEntry = mutationPage.add_entries();
    editEntry->set_conversation_id(conversationId);
    editEntry->set_conversation_sequence(12);
    auto *editHistory = editEntry->mutable_edit();
    editHistory->set_conversation_id(conversationId);
    editHistory->set_conversation_sequence(12);
    editHistory->set_message_id(replyId);
    editHistory->set_content_revision(1);
    editHistory->set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    editHistory->set_content("history edit");
    editHistory->set_actor_account_id("30000000-0000-4000-8000-000000000001");
    editHistory->set_client_operation_id("edit-history-1");
    editHistory->set_occurred_at_epoch_ms(878);
    mutationPage.set_next_sequence(13);
    mutationPage.set_latest_sequence(13);
    const auto mutationEvent = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE, chat::v2::MESSAGE_KIND_RESPONSE,
        mutationHistory.requestId, sessionId, {}, mutationPage));
    check(mutationEvent.nextSequence == 13 && mutationEvent.messages.empty()
              && mutationEvent.reactionChanges.size() == 1
              && mutationEvent.pinChanges.size() == 1
              && mutationEvent.editChanges.size() == 1
              && mutationEvent.editChanges.front().text == "history edit"
              && mutationEvent.pinChanges.front().pinned
              && mutationEvent.reactionChanges.front().messageId == replyId
              && mutationEvent.reactionChanges.front().reaction
                    == V2WindowsMessagingProtocolClient::ReactionKind::Love,
          "mutation-only history must expose reactions and pins on one ordered cursor");

    auto published = second;
    const auto live = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_PUBLISHED, chat::v2::MESSAGE_KIND_EVENT,
        {}, sessionId, {}, published));
    check(live.type == V2WindowsMessagingProtocolClient::EventType::Published
              && live.messages.size() == 1 && live.messages.front().hasReply,
          "uncorrelated live publication must preserve reply identity");

    const auto reactionCommand = client.setReaction(conversationId, replyId,
        V2WindowsMessagingProtocolClient::ReactionKind::Love, true, "reaction-operation-1");
    chat::v2::Envelope reactionEnvelope;
    chat::v2::SetMessageReaction reactionPayload;
    check(reactionEnvelope.ParseFromString(reactionCommand.bytes)
              && reactionEnvelope.message_type() == chat::v2::MESSAGE_TYPE_SET_MESSAGE_REACTION
              && reactionEnvelope.client_message_id().empty()
              && reactionPayload.ParseFromString(reactionEnvelope.payload())
              && reactionPayload.client_operation_id() == "reaction-operation-1",
          "reaction command must preserve the dedicated idempotency identity");
    chat::v2::MessageReactionApplied reactionApplied;
    reactionApplied.set_conversation_id(conversationId);
    reactionApplied.set_message_id(replyId);
    reactionApplied.set_reaction(chat::v2::MESSAGE_REACTION_KIND_LOVE);
    reactionApplied.set_active(true);
    reactionApplied.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    reactionApplied.set_client_operation_id("reaction-operation-1");
    reactionApplied.set_changed(true);
    reactionApplied.set_conversation_sequence(12);
    reactionApplied.set_occurred_at_epoch_ms(880);
    const auto reactionAck = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_APPLIED, chat::v2::MESSAGE_KIND_RESPONSE,
        reactionCommand.requestId, sessionId, {}, reactionApplied));
    check(reactionAck.type == V2WindowsMessagingProtocolClient::EventType::ReactionApplied
              && reactionAck.reactionChange.clientOperationId == "reaction-operation-1",
          "reaction response must correlate the exact operation");
    chat::v2::MessageReactionChangedRecord changed;
    changed.set_conversation_id(conversationId); changed.set_message_id(replyId);
    changed.set_conversation_sequence(12);
    changed.set_reaction(chat::v2::MESSAGE_REACTION_KIND_LOVE); changed.set_active(true);
    changed.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    changed.set_client_operation_id("reaction-operation-1"); changed.set_occurred_at_epoch_ms(880);
    const auto reactionLive = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_CHANGED, chat::v2::MESSAGE_KIND_EVENT,
        {}, sessionId, {}, changed));
    check(reactionLive.type == V2WindowsMessagingProtocolClient::EventType::ReactionChanged
              && reactionLive.conversationSequence == 12,
          "capable live reaction must remain uncorrelated and ordered");

    const auto pinCommand = client.setPin(
        conversationId, replyId, true, "pin-operation-1");
    chat::v2::Envelope pinEnvelope;
    chat::v2::SetMessagePin pinPayload;
    check(pinEnvelope.ParseFromString(pinCommand.bytes)
              && pinEnvelope.message_type() == chat::v2::MESSAGE_TYPE_SET_MESSAGE_PIN
              && pinEnvelope.client_message_id().empty()
              && pinPayload.ParseFromString(pinEnvelope.payload())
              && pinPayload.pinned()
              && pinPayload.client_operation_id() == "pin-operation-1",
          "pin command must preserve the dedicated idempotency identity");
    chat::v2::MessagePinApplied pinApplied;
    pinApplied.set_conversation_id(conversationId);
    pinApplied.set_message_id(replyId);
    pinApplied.set_pinned(true);
    pinApplied.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    pinApplied.set_client_operation_id("pin-operation-1");
    pinApplied.set_changed(true);
    pinApplied.set_conversation_sequence(13);
    pinApplied.set_occurred_at_epoch_ms(890);
    const auto pinAck = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_PIN_APPLIED, chat::v2::MESSAGE_KIND_RESPONSE,
        pinCommand.requestId, sessionId, {}, pinApplied));
    check(pinAck.type == V2WindowsMessagingProtocolClient::EventType::PinApplied
              && pinAck.pinChange.clientOperationId == "pin-operation-1",
          "pin response must correlate the exact operation");
    chat::v2::MessagePinChangedRecord pinChanged;
    pinChanged.set_conversation_id(conversationId);
    pinChanged.set_message_id(replyId);
    pinChanged.set_conversation_sequence(13);
    pinChanged.set_pinned(true);
    pinChanged.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    pinChanged.set_client_operation_id("pin-operation-1");
    pinChanged.set_occurred_at_epoch_ms(890);
    const auto pinLive = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_PIN_CHANGED, chat::v2::MESSAGE_KIND_EVENT,
        {}, sessionId, {}, pinChanged));
    check(pinLive.type == V2WindowsMessagingProtocolClient::EventType::PinChanged
              && pinLive.conversationSequence == 13,
          "capable live pin must remain uncorrelated and ordered");

    const std::string editedText = u8"@李四 edited on Windows";
    const std::vector<V2WindowsMessagingProtocolClient::Mention> editMentions{
        {mentionedAccountId, 0, 7}};
    const auto editCommand = client.editMessage(conversationId, replyId, 1,
        editedText, "edit-operation-1", editMentions);
    chat::v2::Envelope editEnvelope;
    chat::v2::EditMessage editPayload;
    check(editEnvelope.ParseFromString(editCommand.bytes)
              && editEnvelope.message_type() == chat::v2::MESSAGE_TYPE_EDIT_MESSAGE
              && editPayload.ParseFromString(editEnvelope.payload())
              && editPayload.expected_revision() == 1
              && editPayload.content() == editedText
              && editPayload.mentions_size() == 1
              && editPayload.client_operation_id() == "edit-operation-1",
          "edit command must preserve revision, content, mentions, and idempotency identity");
    chat::v2::MessageEditApplied editApplied;
    editApplied.set_conversation_id(conversationId);
    editApplied.set_message_id(replyId);
    editApplied.set_content_revision(2);
    editApplied.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    editApplied.set_content(editedText);
    auto *appliedMention = editApplied.add_mentions();
    appliedMention->set_target_account_id(mentionedAccountId);
    appliedMention->set_start_utf8_byte(0);
    appliedMention->set_length_utf8_bytes(7);
    editApplied.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    editApplied.set_client_operation_id("edit-operation-1");
    editApplied.set_changed(true);
    editApplied.set_conversation_sequence(14);
    editApplied.set_occurred_at_epoch_ms(895);
    const auto editAck = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_EDIT_APPLIED, chat::v2::MESSAGE_KIND_RESPONSE,
        editCommand.requestId, sessionId, {}, editApplied));
    check(editAck.type == V2WindowsMessagingProtocolClient::EventType::EditApplied
              && editAck.editChange.contentRevision == 2
              && editAck.editChange.mentions.size() == 1
              && editAck.editChange.clientOperationId == "edit-operation-1",
          "edit response must correlate the exact revision-safe operation");
    chat::v2::MessageEditedRecord edited;
    edited.set_conversation_id(conversationId);
    edited.set_conversation_sequence(14);
    edited.set_message_id(replyId);
    edited.set_content_revision(2);
    edited.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    edited.set_content(editedText);
    auto *liveMention = edited.add_mentions();
    liveMention->set_target_account_id(mentionedAccountId);
    liveMention->set_start_utf8_byte(0);
    liveMention->set_length_utf8_bytes(7);
    edited.set_actor_account_id("30000000-0000-4000-8000-000000000001");
    edited.set_client_operation_id("edit-operation-1");
    edited.set_occurred_at_epoch_ms(895);
    const auto editLive = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_MESSAGE_EDITED, chat::v2::MESSAGE_KIND_EVENT,
        {}, sessionId, {}, edited));
    check(editLive.type == V2WindowsMessagingProtocolClient::EventType::Edited
              && editLive.conversationSequence == 14
              && editLive.editChange.mentions.size() == 1,
          "capable live edit must remain uncorrelated and ordered");

    const auto invalidHistory = client.readHistory(conversationId, 8, 10);
    auto invalidPage = page;
    invalidPage.mutable_messages(1)->mutable_mentions(0)->set_length_utf8_bytes(2);
    checkThrows([&] {
        client.receive(envelope(chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
                                chat::v2::MESSAGE_KIND_RESPONSE, invalidHistory.requestId,
                                sessionId, {}, invalidPage));
    }, "history mentions that split a Unicode scalar must fail closed");
    invalidPage.mutable_messages(1)->mutable_mentions(0)->set_length_utf8_bytes(7);
    invalidPage.mutable_messages(1)->mutable_reply()->set_target_conversation_sequence(8);
    checkThrows([&] {
        client.receive(envelope(chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE,
                                chat::v2::MESSAGE_KIND_RESPONSE, invalidHistory.requestId,
                                sessionId, {}, invalidPage));
    }, "non-preceding reply references must fail closed");
    check(client.pendingCount() == 1, "invalid history must not consume its correlation");

    chat::v2::ProtocolError error;
    error.set_code(chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED);
    error.set_safe_message("not authorized");
    const auto denied = client.receive(envelope(
        chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR, chat::v2::MESSAGE_KIND_ERROR,
        invalidHistory.requestId, sessionId, {}, error));
    check(denied.type == V2WindowsMessagingProtocolClient::EventType::ProtocolError
              && client.pendingCount() == 0,
          "correlated server errors must complete pending history opaquely");

    const auto pending = client.submitText(conversationId, "local-2", "retry me");
    auto wrong = accepted;
    checkThrows([&] {
        client.receive(envelope(chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED,
                                chat::v2::MESSAGE_KIND_RESPONSE, pending.requestId,
                                sessionId, "other-client-id", wrong));
    }, "ACKs must not reconcile a different client message identity");
    check(client.pendingCount() == 1, "spoofed ACK must preserve pending state");
    client.clearSession();
    check(client.pendingCount() == 0, "disconnect must abandon all in-flight state");

    if (failures) return 1;
    std::cout << "[V2WindowsMessagingProtocolClientTest] PASS\n";
    return 0;
}
