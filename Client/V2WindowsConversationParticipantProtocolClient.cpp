#include "V2WindowsConversationParticipantProtocolClient.h"

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

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("protobuf encode failed");
    return result;
}

template <typename Message>
Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes
            || bytes.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        throw std::runtime_error("participant frame exceeds bound");
    Message result;
    if (!result.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("participant protobuf decode failed");
    return result;
}
}

V2WindowsConversationParticipantProtocolClient::
V2WindowsConversationParticipantProtocolClient(RequestIdFactory factory, Clock clock)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }) {}

void V2WindowsConversationParticipantProtocolClient::bindSession(
        const std::string &sessionId) {
    if (!canonicalUuid(sessionId)) throw std::invalid_argument("invalid participant session");
    m_sessionId = sessionId;
    m_pending.clear();
}

void V2WindowsConversationParticipantProtocolClient::clearSession() {
    m_sessionId.clear();
    m_pending.clear();
}

V2WindowsConversationParticipantProtocolClient::Command
V2WindowsConversationParticipantProtocolClient::list(
        const std::string &conversationId, std::uint32_t limit,
        const std::string &afterAccountId) {
    if (m_sessionId.empty() || !canonicalUuid(conversationId)
            || (!afterAccountId.empty() && !canonicalUuid(afterAccountId))
            || limit < 1 || limit > 100 || m_pending.size() >= 4)
        throw std::invalid_argument("invalid participant request");
    const std::string requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.find(requestId) != m_pending.end())
        throw std::runtime_error("invalid participant request id");
    chat::v2::ListConversationParticipants payload;
    payload.set_conversation_id(conversationId);
    payload.set_after_account_id(afterAccountId);
    payload.set_limit(limit);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    const auto now = m_clock();
    if (now <= 0) throw std::runtime_error("participant clock must be positive");
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(serialize(payload));
    m_pending.emplace(requestId, Pending{conversationId, afterAccountId});
    return {requestId, serialize(envelope)};
}

V2WindowsConversationParticipantProtocolClient::Event
V2WindowsConversationParticipantProtocolClient::receive(const std::string &bytes) {
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (m_sessionId.empty() || envelope.protocol_version() != 2
            || envelope.session_id() != m_sessionId || envelope.sent_at_epoch_ms() <= 0
            || !envelope.client_message_id().empty() || !canonicalUuid(envelope.request_id()))
        throw std::runtime_error("invalid participant envelope");
    const auto pending = m_pending.find(envelope.request_id());
    if (pending == m_pending.end()) throw std::runtime_error("uncorrelated participant response");
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512 || !validUtf8(error.safe_message()))
            throw std::runtime_error("invalid participant protocol error");
        Event result;
        result.type = EventType::ProtocolError;
        result.requestId = envelope.request_id();
        result.conversationId = pending->second.conversationId;
        result.retryable = error.retryable();
        m_pending.erase(pending);
        return result;
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE
            || envelope.message_type() != chat::v2::MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE)
        throw std::runtime_error("participant response type confusion");
    const auto page = parse<chat::v2::ConversationParticipantPage>(envelope.payload());
    if (page.conversation_id() != pending->second.conversationId
            || page.participants_size() > 100
            || (page.has_more() && page.participants().empty()))
        throw std::runtime_error("invalid participant page bounds");
    Event result;
    result.type = EventType::Page;
    result.requestId = envelope.request_id();
    result.conversationId = page.conversation_id();
    const chat::v2::ConversationParticipantRecord *previous = nullptr;
    for (const auto &record : page.participants()) {
        if (!canonicalUuid(record.account_id()) || record.display_name().empty()
                || record.display_name().size() > 400 || !validUtf8(record.display_name())
                || blank(record.display_name()) || scalarCount(record.display_name()) > 100
                || (record.role() != chat::v2::CONVERSATION_ROLE_OWNER
                    && record.role() != chat::v2::CONVERSATION_ROLE_ADMIN
                    && record.role() != chat::v2::CONVERSATION_ROLE_MEMBER))
            throw std::runtime_error("invalid participant record");
        if ((!previous && !pending->second.afterAccountId.empty()
                    && record.account_id() <= pending->second.afterAccountId)
                || (previous && record.account_id() <= previous->account_id()))
            throw std::runtime_error("unordered participant page");
        result.participants.push_back({record.account_id(), record.display_name(),
            record.role() == chat::v2::CONVERSATION_ROLE_OWNER ? Role::Owner
                : record.role() == chat::v2::CONVERSATION_ROLE_ADMIN ? Role::Admin
                                                                    : Role::Member});
        previous = &record;
    }
    if (!previous) {
        if (!page.next_account_id().empty())
            throw std::runtime_error("empty participant page carried cursor");
    } else if (page.next_account_id() != previous->account_id()) {
        throw std::runtime_error("participant cursor differs from last row");
    }
    result.nextAccountId = page.next_account_id();
    result.hasMore = page.has_more();
    m_pending.erase(pending);
    return result;
}

bool V2WindowsConversationParticipantProtocolClient::canonicalUuid(
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

bool V2WindowsConversationParticipantProtocolClient::validUtf8(const std::string &value) {
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

bool V2WindowsConversationParticipantProtocolClient::blank(const std::string &value) {
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

std::size_t V2WindowsConversationParticipantProtocolClient::scalarCount(
        const std::string &value) {
    std::size_t count = 0;
    for (unsigned char byte : value) if ((byte & 0xc0U) != 0x80U) ++count;
    return count;
}

std::string V2WindowsConversationParticipantProtocolClient::randomUuid() {
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
