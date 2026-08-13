#include "V2WindowsConversationDirectoryProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/conversation.pb.h"
#include "chat/v2/envelope.pb.h"

#include <array>
#include <chrono>
#include <google/protobuf/message_lite.h>
#include <limits>
#include <random>
#include <stdexcept>
#include <utility>

namespace {
constexpr std::size_t maximumWireBytes = 1024U * 1024U + 1024U;
constexpr std::uint64_t maximumSignedSequence =
    static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("protobuf encode failed");
    return encoded;
}

template <typename Message>
Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes
            || bytes.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        throw std::runtime_error("directory frame exceeds bound");
    Message result;
    if (!result.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("directory protobuf decode failed");
    return result;
}
}

V2WindowsConversationDirectoryProtocolClient::
V2WindowsConversationDirectoryProtocolClient(RequestIdFactory factory, Clock clock)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }) {}

void V2WindowsConversationDirectoryProtocolClient::bindSession(
        const std::string &sessionId) {
    if (!canonicalUuid(sessionId)) throw std::invalid_argument("invalid directory session");
    m_sessionId = sessionId;
    m_pending.clear();
}

void V2WindowsConversationDirectoryProtocolClient::clearSession() {
    m_sessionId.clear();
    m_pending.clear();
}

V2WindowsConversationDirectoryProtocolClient::Command
V2WindowsConversationDirectoryProtocolClient::list(std::uint32_t limit) {
    return list(limit, Cursor{});
}

V2WindowsConversationDirectoryProtocolClient::Command
V2WindowsConversationDirectoryProtocolClient::list(
        std::uint32_t limit, const Cursor &after) {
    const bool hasCursor = after.updatedAtEpochMs != 0 || !after.conversationId.empty();
    if (m_sessionId.empty() || limit < 1 || limit > 100
            || (hasCursor && (after.updatedAtEpochMs <= 0
                              || !canonicalUuid(after.conversationId)))
            || m_pending.size() >= 4)
        throw std::invalid_argument("invalid directory request");
    const std::string requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.find(requestId) != m_pending.end())
        throw std::runtime_error("invalid directory request id");
    chat::v2::ListConversations payload;
    payload.set_after_updated_at_epoch_ms(after.updatedAtEpochMs);
    payload.set_after_conversation_id(after.conversationId);
    payload.set_limit(limit);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_LIST_CONVERSATIONS);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    const auto now = m_clock();
    if (now <= 0) throw std::runtime_error("directory clock must be positive");
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(serialize(payload));
    m_pending.emplace(requestId, after);
    return {requestId, serialize(envelope)};
}

V2WindowsConversationDirectoryProtocolClient::Event
V2WindowsConversationDirectoryProtocolClient::receive(const std::string &bytes) {
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (m_sessionId.empty() || envelope.protocol_version() != 2
            || envelope.session_id() != m_sessionId || envelope.sent_at_epoch_ms() <= 0
            || !envelope.client_message_id().empty() || !canonicalUuid(envelope.request_id()))
        throw std::runtime_error("invalid directory envelope");
    const auto pending = m_pending.find(envelope.request_id());
    if (pending == m_pending.end()) throw std::runtime_error("uncorrelated directory response");
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512 || !validUtf8(error.safe_message()))
            throw std::runtime_error("invalid directory protocol error");
        Event result;
        result.type = EventType::ProtocolError;
        result.requestId = envelope.request_id();
        result.retryable = error.retryable();
        m_pending.erase(pending);
        return result;
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE
            || envelope.message_type()
                != chat::v2::MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE)
        throw std::runtime_error("directory response type confusion");
    const auto page = parse<chat::v2::ConversationDirectoryPage>(envelope.payload());
    if (page.conversations_size() > 100
            || (page.has_more() && page.conversations().empty()))
        throw std::runtime_error("invalid directory page bounds");
    Event result;
    result.type = EventType::Page;
    result.requestId = envelope.request_id();
    const chat::v2::ConversationDirectoryRecord *previous = nullptr;
    for (const auto &record : page.conversations()) {
        if (!canonicalUuid(record.conversation_id())
                || (record.kind() != chat::v2::CONVERSATION_KIND_DIRECT
                    && record.kind() != chat::v2::CONVERSATION_KIND_GROUP)
                || (record.role() != chat::v2::CONVERSATION_ROLE_OWNER
                    && record.role() != chat::v2::CONVERSATION_ROLE_ADMIN
                    && record.role() != chat::v2::CONVERSATION_ROLE_MEMBER)
                || record.display_name().empty() || record.display_name().size() > 400
                || !validUtf8(record.display_name())
                || unicodeScalarCount(record.display_name()) > 100
                || record.last_read_sequence() > record.latest_sequence()
                || record.latest_sequence() > maximumSignedSequence
                || record.updated_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid directory record");
        if (onlyUnicodeWhitespace(record.display_name()))
            throw std::runtime_error("blank directory name");
        if (!previous && pending->second.updatedAtEpochMs > 0
                && !(pending->second.updatedAtEpochMs > record.updated_at_epoch_ms()
                    || (pending->second.updatedAtEpochMs == record.updated_at_epoch_ms()
                        && pending->second.conversationId > record.conversation_id())))
            throw std::runtime_error("directory page did not advance past cursor");
        if (previous
                && !(previous->updated_at_epoch_ms() > record.updated_at_epoch_ms()
                    || (previous->updated_at_epoch_ms() == record.updated_at_epoch_ms()
                        && previous->conversation_id() > record.conversation_id())))
            throw std::runtime_error("unordered directory page");
        result.conversations.push_back({
            record.conversation_id(),
            record.kind() == chat::v2::CONVERSATION_KIND_DIRECT ? Kind::Direct : Kind::Group,
            record.display_name(),
            record.role() == chat::v2::CONVERSATION_ROLE_OWNER ? Role::Owner
                : record.role() == chat::v2::CONVERSATION_ROLE_ADMIN ? Role::Admin : Role::Member,
            record.latest_sequence(), record.last_read_sequence(),
            record.updated_at_epoch_ms()});
        previous = &record;
    }
    if (!previous) {
        if (page.next_updated_at_epoch_ms() != 0 || !page.next_conversation_id().empty())
            throw std::runtime_error("empty directory page carried cursor");
    } else if (page.next_updated_at_epoch_ms() != previous->updated_at_epoch_ms()
            || page.next_conversation_id() != previous->conversation_id()) {
        throw std::runtime_error("directory cursor differs from last row");
    } else {
        result.next = {page.next_updated_at_epoch_ms(), page.next_conversation_id()};
    }
    result.hasMore = page.has_more();
    m_pending.erase(pending);
    return result;
}

bool V2WindowsConversationDirectoryProtocolClient::canonicalUuid(
        const std::string &value) {
    if (value.size() != 36) return false;
    bool nonzero = false;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-') return false;
        } else if (!((value[index] >= '0' && value[index] <= '9')
                     || (value[index] >= 'a' && value[index] <= 'f'))) return false;
        else nonzero = nonzero || value[index] != '0';
    }
    return nonzero;
}

bool V2WindowsConversationDirectoryProtocolClient::validUtf8(const std::string &value) {
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    while (position < value.size()) {
        const unsigned char first = data[position++];
        if (first <= 0x7fU) continue;
        int trailing = first >= 0xc2U && first <= 0xdfU ? 1
            : first >= 0xe0U && first <= 0xefU ? 2
            : first >= 0xf0U && first <= 0xf4U ? 3 : -1;
        if (trailing < 0 || position + static_cast<std::size_t>(trailing) > value.size()) return false;
        std::uint32_t codepoint = first & (trailing == 1 ? 0x1fU : trailing == 2 ? 0x0fU : 0x07U);
        for (int index = 0; index < trailing; ++index) {
            const unsigned char next = data[position++];
            if ((next & 0xc0U) != 0x80U) return false;
            codepoint = (codepoint << 6U) | (next & 0x3fU);
        }
        if ((trailing == 2 && codepoint < 0x800U)
                || (trailing == 3 && codepoint < 0x10000U)
                || (codepoint >= 0xd800U && codepoint <= 0xdfffU)
                || codepoint > 0x10ffffU) return false;
    }
    return true;
}

bool V2WindowsConversationDirectoryProtocolClient::onlyUnicodeWhitespace(
        const std::string &value) {
    const auto whitespace = [](std::uint32_t codepoint) {
        return (codepoint >= 0x0009U && codepoint <= 0x000dU)
            || codepoint == 0x0020U || codepoint == 0x0085U || codepoint == 0x00a0U
            || codepoint == 0x1680U || (codepoint >= 0x2000U && codepoint <= 0x200aU)
            || codepoint == 0x2028U || codepoint == 0x2029U || codepoint == 0x202fU
            || codepoint == 0x205fU || codepoint == 0x3000U;
    };
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    while (position < value.size()) {
        const unsigned char first = data[position++];
        std::uint32_t codepoint = first;
        int trailing = 0;
        if (first >= 0xc2U && first <= 0xdfU) { trailing = 1; codepoint = first & 0x1fU; }
        else if (first >= 0xe0U && first <= 0xefU) { trailing = 2; codepoint = first & 0x0fU; }
        else if (first >= 0xf0U) { trailing = 3; codepoint = first & 0x07U; }
        for (int index = 0; index < trailing; ++index)
            codepoint = (codepoint << 6U) | (data[position++] & 0x3fU);
        if (!whitespace(codepoint)) return false;
    }
    return true;
}

std::size_t V2WindowsConversationDirectoryProtocolClient::unicodeScalarCount(
        const std::string &value) {
    std::size_t count = 0;
    for (unsigned char byte : value) if ((byte & 0xc0U) != 0x80U) ++count;
    return count;
}

std::string V2WindowsConversationDirectoryProtocolClient::randomUuid() {
    std::array<unsigned char, 16> value{};
    std::random_device random;
    for (auto &byte : value) byte = static_cast<unsigned char>(random());
    value[6] = static_cast<unsigned char>((value[6] & 0x0fU) | 0x40U);
    value[8] = static_cast<unsigned char>((value[8] & 0x3fU) | 0x80U);
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 4 || index == 6 || index == 8 || index == 10) result.push_back('-');
        result.push_back(hex[value[index] >> 4U]);
        result.push_back(hex[value[index] & 0x0fU]);
    }
    return result;
}
