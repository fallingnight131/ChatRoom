#include "V2WindowsMessagingProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"
#include <array>
#include <chrono>
#include <google/protobuf/message_lite.h>
#include <limits>
#include <random>
#include <stdexcept>
#include <utility>

namespace {
constexpr std::size_t maximumEnvelopeBytes = 1024U * 1024U + 1024U;
constexpr std::size_t maximumTextBytes = 65536U;
constexpr std::uint64_t maximumSignedSequence =
    static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());

std::string bytes(const google::protobuf::MessageLite &message) {
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("protobuf encode failed");
    return encoded;
}

template <typename T>
T parse(const std::string &encoded) {
    if (encoded.size() > maximumEnvelopeBytes
            || encoded.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        throw std::runtime_error("protobuf frame exceeds messaging bound");
    T value;
    if (!value.ParseFromArray(encoded.data(), static_cast<int>(encoded.size())))
        throw std::runtime_error("protobuf decode failed");
    return value;
}
}

V2WindowsMessagingProtocolClient::V2WindowsMessagingProtocolClient(
        RequestIdFactory factory, Clock clock)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }) {}

void V2WindowsMessagingProtocolClient::bindSession(const std::string &sessionId) {
    if (!canonicalUuid(sessionId)) throw std::invalid_argument("invalid authenticated session");
    m_sessionId = sessionId;
    m_pending.clear();
}

void V2WindowsMessagingProtocolClient::clearSession() {
    m_sessionId.clear();
    m_pending.clear();
}

std::size_t V2WindowsMessagingProtocolClient::pendingCount() const {
    return m_pending.size();
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::submitText(
        const std::string &conversationId, const std::string &clientMessageId,
        const std::string &text) {
    if (!canonicalUuid(conversationId) || !boundedIdentifier(clientMessageId, true)
            || text.empty() || text.size() > maximumTextBytes || !validUtf8(text))
        throw std::invalid_argument("invalid text submission");
    chat::v2::SubmitMessage payload;
    payload.set_conversation_id(conversationId);
    payload.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    payload.set_content(text);
    Pending pending;
    pending.type = PendingType::Submit; pending.conversationId = conversationId;
    pending.clientMessageId = clientMessageId;
    return command(chat::v2::MESSAGE_TYPE_SUBMIT_MESSAGE, bytes(payload), clientMessageId,
                   std::move(pending));
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::submitReplyText(
        const std::string &conversationId, const std::string &clientMessageId,
        const std::string &targetMessageId, const std::string &text) {
    if (!canonicalUuid(conversationId) || !canonicalUuid(targetMessageId)
            || !boundedIdentifier(clientMessageId, true) || text.empty()
            || text.size() > maximumTextBytes || !validUtf8(text))
        throw std::invalid_argument("invalid reply submission");
    chat::v2::SubmitReplyMessage payload;
    payload.set_conversation_id(conversationId);
    payload.set_target_message_id(targetMessageId);
    payload.set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    payload.set_content(text);
    Pending pending;
    pending.type = PendingType::Reply; pending.conversationId = conversationId;
    pending.clientMessageId = clientMessageId;
    return command(chat::v2::MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE, bytes(payload), clientMessageId,
                   std::move(pending));
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::readHistory(
        const std::string &conversationId, std::uint64_t afterSequence,
        std::uint32_t limit) {
    if (!canonicalUuid(conversationId) || afterSequence > maximumSignedSequence
            || limit < 1 || limit > 100)
        throw std::invalid_argument("invalid history request");
    chat::v2::ReadMessageHistory payload;
    payload.set_conversation_id(conversationId);
    payload.set_after_sequence(afterSequence);
    payload.set_limit(limit);
    Pending pending;
    pending.type = PendingType::History; pending.conversationId = conversationId;
    pending.afterSequence = afterSequence;
    return command(chat::v2::MESSAGE_TYPE_READ_MESSAGE_HISTORY, bytes(payload), {},
                   std::move(pending));
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::setReaction(
        const std::string &conversationId, const std::string &messageId,
        ReactionKind reaction, bool active, const std::string &clientOperationId) {
    const int value = static_cast<int>(reaction);
    if (!canonicalUuid(conversationId) || !canonicalUuid(messageId)
            || value < 1 || value > 6 || !boundedIdentifier(clientOperationId, true))
        throw std::invalid_argument("invalid reaction command");
    chat::v2::SetMessageReaction payload;
    payload.set_conversation_id(conversationId);
    payload.set_message_id(messageId);
    payload.set_reaction(static_cast<chat::v2::MessageReactionKind>(value));
    payload.set_active(active);
    payload.set_client_operation_id(clientOperationId);
    Pending pending;
    pending.type = PendingType::Reaction;
    pending.conversationId = conversationId;
    pending.messageId = messageId;
    pending.reaction = reaction;
    pending.active = active;
    pending.clientOperationId = clientOperationId;
    return command(chat::v2::MESSAGE_TYPE_SET_MESSAGE_REACTION, bytes(payload), {},
                   std::move(pending));
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::setPin(
        const std::string &conversationId, const std::string &messageId,
        bool pinned, const std::string &clientOperationId) {
    if (!canonicalUuid(conversationId) || !canonicalUuid(messageId)
            || !boundedIdentifier(clientOperationId, true))
        throw std::invalid_argument("invalid pin command");
    chat::v2::SetMessagePin payload;
    payload.set_conversation_id(conversationId); payload.set_message_id(messageId);
    payload.set_pinned(pinned); payload.set_client_operation_id(clientOperationId);
    Pending pending; pending.type = PendingType::Pin; pending.conversationId = conversationId;
    pending.messageId = messageId; pending.pinned = pinned;
    pending.clientOperationId = clientOperationId;
    return command(chat::v2::MESSAGE_TYPE_SET_MESSAGE_PIN, bytes(payload), {}, std::move(pending));
}

V2WindowsMessagingProtocolClient::Command
V2WindowsMessagingProtocolClient::command(
        int messageType, const std::string &payload, const std::string &clientMessageId,
        Pending pending) {
    if (m_sessionId.empty()) throw std::logic_error("not authenticated");
    if (m_pending.size() >= 32) throw std::runtime_error("too many pending messaging requests");
    const std::string requestId = m_factory();
    if (!boundedIdentifier(requestId, true) || m_pending.find(requestId) != m_pending.end())
        throw std::runtime_error("invalid request id");
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(messageType);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    envelope.set_client_message_id(clientMessageId);
    const auto sentAtEpochMs = m_clock();
    if (sentAtEpochMs <= 0) throw std::runtime_error("clock must be positive");
    envelope.set_sent_at_epoch_ms(sentAtEpochMs);
    envelope.set_payload(payload);
    m_pending.emplace(requestId, std::move(pending));
    return {requestId, clientMessageId, bytes(envelope)};
}

V2WindowsMessagingProtocolClient::Event
V2WindowsMessagingProtocolClient::receive(const std::string &encoded) {
    const auto envelope = parse<chat::v2::Envelope>(encoded);
    if (envelope.protocol_version() != 2 || envelope.sent_at_epoch_ms() <= 0
            || envelope.session_id() != m_sessionId || m_sessionId.empty()
            || !boundedIdentifier(envelope.request_id(), false)
            || !boundedIdentifier(envelope.client_message_id(), false)
            || envelope.payload().size() > 1024U * 1024U)
        throw std::runtime_error("invalid messaging envelope");

    auto decodeMessage = [&](const chat::v2::MessageRecord &record) {
        if (!canonicalUuid(record.conversation_id()) || !canonicalUuid(record.message_id())
                || !canonicalUuid(record.sender_account_id())
                || !canonicalUuid(record.sender_device_id())
                || !boundedIdentifier(record.client_message_id(), true)
                || record.conversation_sequence() == 0
                || record.conversation_sequence() > maximumSignedSequence
                || record.content_type() != chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8
                || record.content().empty() || record.content().size() > maximumTextBytes
                || !validUtf8(record.content()) || record.accepted_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid message record");
        Message result;
        result.conversationId = record.conversation_id();
        result.messageId = record.message_id();
        result.conversationSequence = record.conversation_sequence();
        result.senderAccountId = record.sender_account_id();
        result.senderDeviceId = record.sender_device_id();
        result.clientMessageId = record.client_message_id();
        result.text = record.content();
        result.acceptedAtEpochMs = record.accepted_at_epoch_ms();
        result.hasReply = record.has_reply();
        if (record.has_reply()) {
            const auto &reply = record.reply();
            if (!canonicalUuid(reply.target_message_id())
                    || !canonicalUuid(reply.target_sender_account_id())
                    || reply.target_conversation_sequence() == 0
                    || reply.target_conversation_sequence() >= record.conversation_sequence())
                throw std::runtime_error("invalid reply reference");
            result.reply = {reply.target_message_id(), reply.target_conversation_sequence(),
                            reply.target_sender_account_id()};
        }
        return result;
    };

    auto decodeReaction = [&](const chat::v2::MessageReactionChangedRecord &reaction) {
        if (!canonicalUuid(reaction.conversation_id())
                || !canonicalUuid(reaction.message_id())
                || !canonicalUuid(reaction.actor_account_id())
                || !boundedIdentifier(reaction.client_operation_id(), true)
                || reaction.conversation_sequence() == 0
                || reaction.conversation_sequence() > maximumSignedSequence
                || reaction.occurred_at_epoch_ms() <= 0
                || reaction.reaction() < chat::v2::MESSAGE_REACTION_KIND_LIKE
                || reaction.reaction() > chat::v2::MESSAGE_REACTION_KIND_ANGRY)
            throw std::runtime_error("invalid reaction change");
        return ReactionChange{reaction.conversation_id(), reaction.conversation_sequence(),
            reaction.message_id(), static_cast<ReactionKind>(reaction.reaction()),
            reaction.active(), reaction.actor_account_id(), reaction.client_operation_id(),
            reaction.occurred_at_epoch_ms()};
    };
    auto decodePin = [&](const chat::v2::MessagePinChangedRecord &pin) {
        if (!canonicalUuid(pin.conversation_id()) || !canonicalUuid(pin.message_id())
                || !canonicalUuid(pin.actor_account_id())
                || !boundedIdentifier(pin.client_operation_id(), true)
                || pin.conversation_sequence() == 0
                || pin.conversation_sequence() > maximumSignedSequence
                || pin.occurred_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid pin change");
        return PinChange{pin.conversation_id(), pin.conversation_sequence(), pin.message_id(),
            pin.pinned(), pin.actor_account_id(), pin.client_operation_id(),
            pin.occurred_at_epoch_ms()};
    };

    if (envelope.kind() == chat::v2::MESSAGE_KIND_EVENT
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_PUBLISHED) {
        if (!envelope.request_id().empty() || !envelope.client_message_id().empty())
            throw std::runtime_error("published event must not claim request correlation");
        auto message = decodeMessage(parse<chat::v2::MessageRecord>(envelope.payload()));
        Event result;
        result.type = EventType::Published;
        result.conversationId = message.conversationId;
        result.messageId = message.messageId;
        result.conversationSequence = message.conversationSequence;
        result.messages.push_back(std::move(message));
        return result;
    }
    if (envelope.kind() == chat::v2::MESSAGE_KIND_EVENT
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_PIN_CHANGED) {
        if (!envelope.request_id().empty() || !envelope.client_message_id().empty())
            throw std::runtime_error("pin event must not claim request correlation");
        Event result; result.type = EventType::PinChanged;
        result.pinChange = decodePin(parse<chat::v2::MessagePinChangedRecord>(envelope.payload()));
        result.conversationId = result.pinChange.conversationId;
        result.messageId = result.pinChange.messageId;
        result.conversationSequence = result.pinChange.conversationSequence;
        return result;
    }
    if (envelope.kind() == chat::v2::MESSAGE_KIND_EVENT
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_CHANGED) {
        if (!envelope.request_id().empty() || !envelope.client_message_id().empty())
            throw std::runtime_error("reaction event must not claim request correlation");
        Event result;
        result.type = EventType::ReactionChanged;
        result.reactionChange = decodeReaction(
            parse<chat::v2::MessageReactionChangedRecord>(envelope.payload()));
        result.conversationId = result.reactionChange.conversationId;
        result.messageId = result.reactionChange.messageId;
        result.conversationSequence = result.reactionChange.conversationSequence;
        return result;
    }

    const auto position = m_pending.find(envelope.request_id());
    if (position == m_pending.end()) throw std::runtime_error("uncorrelated messaging response");
    const Pending pending = position->second;
    if (envelope.client_message_id() != pending.clientMessageId)
        throw std::runtime_error("wrong client message correlation");
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512 || !validUtf8(error.safe_message()))
            throw std::runtime_error("invalid protocol error");
        m_pending.erase(position);
        Event result;
        result.type = EventType::ProtocolError;
        result.requestId = envelope.request_id();
        result.clientMessageId = envelope.client_message_id();
        result.conversationId = pending.conversationId;
        result.retryable = error.retryable();
        if (pending.type == PendingType::Reaction) {
            result.messageId = pending.messageId;
            result.reactionChange = {pending.conversationId, 0, pending.messageId,
                pending.reaction, pending.active, {}, pending.clientOperationId, 0};
        }
        if (pending.type == PendingType::Pin) {
            result.messageId = pending.messageId;
            result.pinChange = {pending.conversationId, 0, pending.messageId, pending.pinned,
                {}, pending.clientOperationId, 0};
        }
        return result;
    }
    if ((pending.type == PendingType::Submit || pending.type == PendingType::Reply)
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_ACCEPTED) {
        const auto accepted = parse<chat::v2::MessageAccepted>(envelope.payload());
        if (accepted.conversation_id() != pending.conversationId
                || !canonicalUuid(accepted.message_id())
                || accepted.conversation_sequence() == 0
                || accepted.conversation_sequence() > maximumSignedSequence
                || accepted.accepted_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid acceptance response");
        m_pending.erase(position);
        Event result;
        result.type = EventType::Accepted;
        result.requestId = envelope.request_id();
        result.clientMessageId = envelope.client_message_id();
        result.conversationId = accepted.conversation_id();
        result.messageId = accepted.message_id();
        result.conversationSequence = accepted.conversation_sequence();
        result.acceptedAtEpochMs = accepted.accepted_at_epoch_ms();
        result.duplicate = accepted.duplicate();
        return result;
    }
    if (pending.type == PendingType::Reaction
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_REACTION_APPLIED) {
        const auto applied = parse<chat::v2::MessageReactionApplied>(envelope.payload());
        if (applied.conversation_id() != pending.conversationId
                || applied.message_id() != pending.messageId
                || applied.client_operation_id() != pending.clientOperationId
                || applied.reaction() != static_cast<int>(pending.reaction)
                || applied.active() != pending.active
                || !canonicalUuid(applied.actor_account_id())
                || applied.occurred_at_epoch_ms() <= 0
                || applied.conversation_sequence() > maximumSignedSequence
                || applied.changed() != (applied.conversation_sequence() > 0))
            throw std::runtime_error("invalid reaction application");
        m_pending.erase(position);
        Event result;
        result.type = EventType::ReactionApplied;
        result.requestId = envelope.request_id();
        result.conversationId = applied.conversation_id();
        result.messageId = applied.message_id();
        result.conversationSequence = applied.conversation_sequence();
        result.reactionChange = {applied.conversation_id(), applied.conversation_sequence(),
            applied.message_id(), pending.reaction, applied.active(), applied.actor_account_id(),
            applied.client_operation_id(), applied.occurred_at_epoch_ms()};
        return result;
    }
    if (pending.type == PendingType::Pin
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_PIN_APPLIED) {
        const auto applied = parse<chat::v2::MessagePinApplied>(envelope.payload());
        if (applied.conversation_id() != pending.conversationId
                || applied.message_id() != pending.messageId
                || applied.client_operation_id() != pending.clientOperationId
                || applied.pinned() != pending.pinned || !canonicalUuid(applied.actor_account_id())
                || applied.occurred_at_epoch_ms() <= 0
                || applied.conversation_sequence() > maximumSignedSequence
                || applied.changed() != (applied.conversation_sequence() > 0))
            throw std::runtime_error("invalid pin application");
        m_pending.erase(position); Event result; result.type = EventType::PinApplied;
        result.requestId = envelope.request_id(); result.conversationId = applied.conversation_id();
        result.messageId = applied.message_id(); result.conversationSequence = applied.conversation_sequence();
        result.pinChange = {applied.conversation_id(), applied.conversation_sequence(),
            applied.message_id(), applied.pinned(), applied.actor_account_id(),
            applied.client_operation_id(), applied.occurred_at_epoch_ms()};
        return result;
    }
    if (pending.type == PendingType::History
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE) {
        const auto page = parse<chat::v2::MessageHistoryPage>(envelope.payload());
        if (page.conversation_id() != pending.conversationId || page.messages_size() > 100
                || page.entries_size() > 100 || page.next_sequence() > maximumSignedSequence
                || page.latest_sequence() > maximumSignedSequence)
            throw std::runtime_error("invalid history page");
        std::vector<Message> messages;
        std::uint64_t previous = 0;
        for (const auto &record : page.messages()) {
            auto message = decodeMessage(record);
            if (message.conversationId != pending.conversationId
                    || message.conversationSequence <= previous
                    || message.conversationSequence <= pending.afterSequence)
                throw std::runtime_error("unordered history page");
            previous = message.conversationSequence;
            messages.push_back(std::move(message));
        }
        std::uint64_t previousEntry = 0;
        std::vector<Message> entryMessages;
        std::vector<std::string> recalledMessageIds;
        std::vector<std::string> deletedMessageIds;
        std::vector<ReactionChange> reactionChanges;
        std::vector<PinChange> pinChanges;
        for (const auto &entry : page.entries()) {
            if (entry.conversation_id() != pending.conversationId
                    || entry.conversation_sequence() == 0
                    || entry.conversation_sequence() > maximumSignedSequence
                    || entry.conversation_sequence() <= previousEntry
                    || entry.conversation_sequence() <= pending.afterSequence)
                throw std::runtime_error("unordered history entries");
            switch (entry.detail_case()) {
            case chat::v2::ConversationEntryRecord::kMessage: {
                auto message = decodeMessage(entry.message());
                if (message.conversationSequence != entry.conversation_sequence()
                        || message.conversationId != entry.conversation_id())
                    throw std::runtime_error("message entry identity differs");
                entryMessages.push_back(std::move(message));
                break;
            }
            case chat::v2::ConversationEntryRecord::kRecall: {
                const auto &recall = entry.recall();
                if (recall.conversation_id() != entry.conversation_id()
                        || recall.conversation_sequence() != entry.conversation_sequence()
                        || !canonicalUuid(recall.message_id())
                        || !canonicalUuid(recall.actor_account_id())
                        || (recall.source() != "V2" && recall.source() != "V1_IMPORT")
                        || recall.occurred_at_epoch_ms() < 0)
                    throw std::runtime_error("invalid recall entry");
                recalledMessageIds.push_back(recall.message_id());
                break;
            }
            case chat::v2::ConversationEntryRecord::kDeletion: {
                const auto &deletion = entry.deletion();
                if (deletion.conversation_id() != entry.conversation_id()
                        || deletion.conversation_sequence() != entry.conversation_sequence()
                        || !canonicalUuid(deletion.actor_account_id())
                        || (deletion.source() != "V2" && deletion.source() != "V1_IMPORT")
                        || deletion.client_operation_id().empty()
                        || deletion.client_operation_id().size() > 128
                        || deletion.message_ids_size() > 1000
                        || deletion.occurred_at_epoch_ms() <= 0)
                    throw std::runtime_error("invalid deletion entry");
                for (const auto &messageId : deletion.message_ids()) {
                    if (!canonicalUuid(messageId))
                        throw std::runtime_error("invalid deletion target");
                    deletedMessageIds.push_back(messageId);
                }
                break;
            }
            case chat::v2::ConversationEntryRecord::kReaction: {
                const auto &reaction = entry.reaction();
                if (reaction.conversation_id() != entry.conversation_id()
                        || reaction.conversation_sequence()
                                != entry.conversation_sequence()
                        || !canonicalUuid(reaction.message_id())
                        || !canonicalUuid(reaction.actor_account_id())
                        || !boundedIdentifier(reaction.client_operation_id(), true)
                        || reaction.occurred_at_epoch_ms() <= 0)
                    throw std::runtime_error("invalid reaction entry");
                ReactionKind kind;
                switch (reaction.reaction()) {
                case chat::v2::MESSAGE_REACTION_KIND_LIKE: kind = ReactionKind::Like; break;
                case chat::v2::MESSAGE_REACTION_KIND_LOVE: kind = ReactionKind::Love; break;
                case chat::v2::MESSAGE_REACTION_KIND_LAUGH: kind = ReactionKind::Laugh; break;
                case chat::v2::MESSAGE_REACTION_KIND_SURPRISED:
                    kind = ReactionKind::Surprised; break;
                case chat::v2::MESSAGE_REACTION_KIND_SAD: kind = ReactionKind::Sad; break;
                case chat::v2::MESSAGE_REACTION_KIND_ANGRY: kind = ReactionKind::Angry; break;
                case chat::v2::MESSAGE_REACTION_KIND_UNSPECIFIED:
                default: throw std::runtime_error("unsupported reaction entry");
                }
                reactionChanges.push_back({reaction.conversation_id(),
                    reaction.conversation_sequence(), reaction.message_id(), kind,
                    reaction.active(), reaction.actor_account_id(),
                    reaction.client_operation_id(), reaction.occurred_at_epoch_ms()});
                break;
            }
            case chat::v2::ConversationEntryRecord::kPin: {
                const auto pin = decodePin(entry.pin());
                if (pin.conversationId != entry.conversation_id()
                        || pin.conversationSequence != entry.conversation_sequence())
                    throw std::runtime_error("pin entry identity differs");
                pinChanges.push_back(pin); break;
            }
            case chat::v2::ConversationEntryRecord::kEdit:
                throw std::runtime_error("edit entry received without capability");
            case chat::v2::ConversationEntryRecord::DETAIL_NOT_SET:
                throw std::runtime_error("history entry detail is required");
            }
            previousEntry = entry.conversation_sequence();
        }
        const std::uint64_t lastSequence = previousEntry == 0 ? previous : previousEntry;
        if (lastSequence != 0 && page.next_sequence() != lastSequence)
            throw std::runtime_error("history cursor differs from last entry");
        if (page.next_sequence() < pending.afterSequence
                || (page.has_more() && page.next_sequence() <= pending.afterSequence))
            throw std::runtime_error("history cursor did not advance");
        if (page.next_sequence() > page.latest_sequence())
            throw std::runtime_error("history cursor exceeds latest sequence");
        m_pending.erase(position);
        Event result;
        result.type = EventType::HistoryPage;
        result.requestId = envelope.request_id();
        result.conversationId = page.conversation_id();
        result.messages = page.entries_size() == 0
            ? std::move(messages) : std::move(entryMessages);
        result.recalledMessageIds = std::move(recalledMessageIds);
        result.deletedMessageIds = std::move(deletedMessageIds);
        result.reactionChanges = std::move(reactionChanges);
        result.pinChanges = std::move(pinChanges);
        result.nextSequence = page.next_sequence();
        result.latestSequence = page.latest_sequence();
        result.hasMore = page.has_more();
        return result;
    }
    throw std::runtime_error("messaging response type confusion");
}

bool V2WindowsMessagingProtocolClient::canonicalUuid(const std::string &value) {
    if (value.size() != 36) return false;
    bool nonzero = false;
    for (std::size_t position = 0; position < value.size(); ++position) {
        if (position == 8 || position == 13 || position == 18 || position == 23) {
            if (value[position] != '-') return false;
            continue;
        }
        const char character = value[position];
        if (!((character >= '0' && character <= '9')
              || (character >= 'a' && character <= 'f')))
            return false;
        nonzero = nonzero || character != '0';
    }
    return nonzero;
}

bool V2WindowsMessagingProtocolClient::boundedIdentifier(
        const std::string &value, bool required) {
    return (!required || !value.empty()) && value.size() <= 128 && validUtf8(value);
}

bool V2WindowsMessagingProtocolClient::validUtf8(const std::string &value) {
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    while (position < value.size()) {
        const unsigned char first = data[position++];
        if (first <= 0x7fU) continue;
        int trailing = 0;
        std::uint32_t codepoint = 0;
        if (first >= 0xc2U && first <= 0xdfU) { trailing = 1; codepoint = first & 0x1fU; }
        else if (first >= 0xe0U && first <= 0xefU) { trailing = 2; codepoint = first & 0x0fU; }
        else if (first >= 0xf0U && first <= 0xf4U) { trailing = 3; codepoint = first & 0x07U; }
        else return false;
        if (position + static_cast<std::size_t>(trailing) > value.size()) return false;
        for (int index = 0; index < trailing; ++index) {
            const unsigned char next = data[position++];
            if ((next & 0xc0U) != 0x80U) return false;
            codepoint = (codepoint << 6U) | (next & 0x3fU);
        }
        if ((trailing == 2 && codepoint < 0x800U)
                || (trailing == 3 && codepoint < 0x10000U)
                || (codepoint >= 0xd800U && codepoint <= 0xdfffU)
                || codepoint > 0x10ffffU)
            return false;
    }
    return true;
}

std::string V2WindowsMessagingProtocolClient::randomUuid() {
    std::array<unsigned char, 16> value{};
    std::random_device random;
    for (auto &byte : value) byte = static_cast<unsigned char>(random());
    value[6] = static_cast<unsigned char>((value[6] & 0x0fU) | 0x40U);
    value[8] = static_cast<unsigned char>((value[8] & 0x3fU) | 0x80U);
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(36);
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 4 || index == 6 || index == 8 || index == 10) result.push_back('-');
        result.push_back(hex[value[index] >> 4U]);
        result.push_back(hex[value[index] & 0x0fU]);
    }
    return result;
}
