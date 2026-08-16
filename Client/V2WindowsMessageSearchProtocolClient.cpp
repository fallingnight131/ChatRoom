#include "V2WindowsMessageSearchProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"
#include <algorithm>
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
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("search encode failed");
    return result;
}
template <typename Message> Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes) throw std::runtime_error("search frame too large");
    Message result;
    if (!result.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("search decode failed");
    return result;
}
}

V2WindowsMessageSearchProtocolClient::V2WindowsMessageSearchProtocolClient(
        RequestIdFactory factory, Clock clock, bool enableForwarding)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }), m_enableForwarding(enableForwarding) {}

void V2WindowsMessageSearchProtocolClient::bindSession(const std::string &sessionId) {
    if (!canonicalUuid(sessionId)) throw std::invalid_argument("invalid search session");
    m_sessionId = sessionId;
    m_pending.clear();
}
void V2WindowsMessageSearchProtocolClient::clearSession() {
    m_sessionId.clear();
    m_pending.clear();
}

V2WindowsMessageSearchProtocolClient::Command
V2WindowsMessageSearchProtocolClient::search(
        const std::string &conversationId, const std::string &literalQuery,
        std::uint64_t beforeSequence, std::uint32_t limit) {
    if (m_sessionId.empty() || !canonicalUuid(conversationId)
            || literalQuery.empty() || literalQuery.size() > 128
            || !validUtf8(literalQuery) || !stripped(literalQuery)
            || beforeSequence > maximumSignedSequence || limit < 1 || limit > 50
            || m_pending.size() >= 4)
        throw std::invalid_argument("invalid search request");
    const auto requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.count(requestId))
        throw std::runtime_error("invalid search request id");
    chat::v2::SearchConversationMessages payload;
    payload.set_conversation_id(conversationId);
    payload.set_literal_query(literalQuery);
    payload.set_before_sequence(beforeSequence);
    payload.set_limit(limit);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    const auto now = m_clock();
    if (now <= 0) throw std::runtime_error("search clock must be positive");
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(serialize(payload));
    m_pending.emplace(requestId, Pending{conversationId, beforeSequence});
    return {requestId, serialize(envelope)};
}

V2WindowsMessageSearchProtocolClient::Event
V2WindowsMessageSearchProtocolClient::receive(const std::string &bytes) {
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (m_sessionId.empty() || envelope.protocol_version() != 2
            || envelope.session_id() != m_sessionId || envelope.sent_at_epoch_ms() <= 0
            || !envelope.client_message_id().empty() || !canonicalUuid(envelope.request_id()))
        throw std::runtime_error("invalid search envelope");
    const auto pending = m_pending.find(envelope.request_id());
    if (pending == m_pending.end()) throw std::runtime_error("uncorrelated search response");
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512 || !validUtf8(error.safe_message()))
            throw std::runtime_error("invalid search error");
        Event result;
        result.requestId = envelope.request_id();
        result.retryable = error.retryable();
        m_pending.erase(pending);
        return result;
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE
            || envelope.message_type() != chat::v2::MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE)
        throw std::runtime_error("search response type confusion");
    const auto page = parse<chat::v2::ConversationMessageSearchPage>(envelope.payload());
    if (!canonicalUuid(page.conversation_id())
            || page.conversation_id() != pending->second.conversationId || page.hits_size() > 50
            || (page.has_more() && page.hits().empty()))
        throw std::runtime_error("invalid search page");
    Event result;
    result.type = EventType::Page;
    result.requestId = envelope.request_id();
    result.conversationId = page.conversation_id();
    std::uint64_t previous = pending->second.beforeSequence;
    for (const auto &hit : page.hits()) {
        if (!canonicalUuid(hit.conversation_id()) || hit.conversation_id() != page.conversation_id()
                || !canonicalUuid(hit.message_id()) || !canonicalUuid(hit.sender_account_id())
                || !canonicalUuid(hit.sender_device_id()) || !canonicalUuid(hit.client_message_id())
                || hit.conversation_sequence() == 0 || hit.conversation_sequence() > maximumSignedSequence
                || (previous != 0 && hit.conversation_sequence() >= previous)
                || hit.content_type() != chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8
                || hit.content().empty() || hit.content().size() > 16 * 1024
                || !validUtf8(hit.content()) || hit.accepted_at_epoch_ms() <= 0
                || hit.content_revision() > 1'000'000
                || (hit.content_revision() == 0) != (hit.edited_at_epoch_ms() == 0)
                || hit.edited_at_epoch_ms() < 0
                || hit.mentions_size() > 100 || (hit.forwarded() && !m_enableForwarding))
            throw std::runtime_error("invalid search hit");
        for (const auto &mention : hit.mentions())
            if (!canonicalUuid(mention.target_account_id()) || mention.length_utf8_bytes() == 0
                    || static_cast<std::size_t>(mention.start_utf8_byte())
                        + static_cast<std::size_t>(mention.length_utf8_bytes())
                        > hit.content().size())
                throw std::runtime_error("invalid search mention");
        if (hit.has_reply() && (!canonicalUuid(hit.reply().target_message_id())
                || !canonicalUuid(hit.reply().target_sender_account_id())
                || hit.reply().target_conversation_sequence() == 0
                || hit.reply().target_conversation_sequence() >= hit.conversation_sequence()))
            throw std::runtime_error("invalid search reply");
        result.hits.push_back({hit.conversation_id(), hit.message_id(),
            hit.conversation_sequence(), hit.sender_account_id(), hit.sender_device_id(),
            hit.client_message_id(), hit.content(), hit.accepted_at_epoch_ms(),
            hit.content_revision(), hit.edited_at_epoch_ms()});
        previous = hit.conversation_sequence();
    }
    const auto expectedCursor = result.hits.empty() ? 0 : result.hits.back().conversationSequence;
    if (page.next_before_sequence() != expectedCursor
            || (page.has_more() && page.next_before_sequence() == 0))
        throw std::runtime_error("invalid search cursor");
    result.nextBeforeSequence = page.next_before_sequence();
    result.hasMore = page.has_more();
    m_pending.erase(pending);
    return result;
}

bool V2WindowsMessageSearchProtocolClient::canonicalUuid(const std::string &value) {
    if (value.size() != 36) return false;
    bool nonzero = false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (value[i] != '-') return false; }
        else if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f'))) return false;
        else nonzero = nonzero || value[i] != '0';
    }
    return nonzero;
}
bool V2WindowsMessageSearchProtocolClient::validUtf8(const std::string &value) {
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t pos = 0;
    while (pos < value.size()) {
        const auto first = data[pos++];
        if (first <= 0x7fU) continue;
        const int trailing = first >= 0xc2U && first <= 0xdfU ? 1
            : first >= 0xe0U && first <= 0xefU ? 2 : first >= 0xf0U && first <= 0xf4U ? 3 : -1;
        if (trailing < 0 || pos + static_cast<std::size_t>(trailing) > value.size()) return false;
        std::uint32_t cp = first & (trailing == 1 ? 0x1fU : trailing == 2 ? 0x0fU : 0x07U);
        for (int i = 0; i < trailing; ++i) { const auto next = data[pos++]; if ((next & 0xc0U) != 0x80U) return false; cp = (cp << 6U) | (next & 0x3fU); }
        if ((trailing == 2 && cp < 0x800U) || (trailing == 3 && cp < 0x10000U)
                || (cp >= 0xd800U && cp <= 0xdfffU) || cp > 0x10ffffU) return false;
    }
    return true;
}
bool V2WindowsMessageSearchProtocolClient::stripped(const std::string &value) {
    const auto whitespace = [](std::uint32_t cp) {
        return (cp >= 0x0009U && cp <= 0x000dU) || cp == 0x0020U
            || cp == 0x0085U || cp == 0x00a0U || cp == 0x1680U
            || (cp >= 0x2000U && cp <= 0x200aU) || cp == 0x2028U
            || cp == 0x2029U || cp == 0x202fU || cp == 0x205fU || cp == 0x3000U;
    };
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    std::uint32_t firstCodepoint = 0;
    std::uint32_t lastCodepoint = 0;
    while (position < value.size()) {
        const unsigned char first = data[position++];
        std::uint32_t codepoint = first;
        int trailing = 0;
        if (first >= 0xc2U && first <= 0xdfU) { trailing = 1; codepoint = first & 0x1fU; }
        else if (first >= 0xe0U && first <= 0xefU) { trailing = 2; codepoint = first & 0x0fU; }
        else if (first >= 0xf0U) { trailing = 3; codepoint = first & 0x07U; }
        for (int index = 0; index < trailing; ++index)
            codepoint = (codepoint << 6U) | (data[position++] & 0x3fU);
        if (codepoint == 0) return false;
        if (firstCodepoint == 0) firstCodepoint = codepoint;
        lastCodepoint = codepoint;
    }
    return firstCodepoint != 0 && !whitespace(firstCodepoint) && !whitespace(lastCodepoint);
}
std::string V2WindowsMessageSearchProtocolClient::randomUuid() {
    std::array<unsigned char, 16> value{}; std::random_device random;
    for (auto &byte : value) byte = static_cast<unsigned char>(random());
    value[6] = (value[6] & 0x0fU) | 0x40U; value[8] = (value[8] & 0x3fU) | 0x80U;
    static constexpr char hex[] = "0123456789abcdef"; std::string result;
    for (std::size_t i = 0; i < value.size(); ++i) { if (i == 4 || i == 6 || i == 8 || i == 10) result += '-'; result += hex[value[i] >> 4U]; result += hex[value[i] & 0x0fU]; }
    return result;
}
