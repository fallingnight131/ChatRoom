#include "V2WindowsAccountBlockProtocolClient.h"

#include "chat/v2/contact.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

#include <array>
#include <chrono>
#include <google/protobuf/message_lite.h>
#include <random>
#include <stdexcept>
#include <utility>

namespace {
constexpr std::size_t maximumWireBytes = 1024U * 1024U + 1024U;
constexpr std::size_t maximumPendingOperations = 8;

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result))
        throw std::runtime_error("account block encode failed");
    return result;
}

template <typename Message> Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes)
        throw std::runtime_error("account block frame too large");
    Message result;
    if (!result.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("account block decode failed");
    return result;
}
}

V2WindowsAccountBlockProtocolClient::V2WindowsAccountBlockProtocolClient(
        RequestIdFactory factory, Clock clock)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }) {}

void V2WindowsAccountBlockProtocolClient::bindSession(
        const std::string &sessionId, const std::string &actorAccountId) {
    if (!canonicalUuid(sessionId) || !canonicalUuid(actorAccountId))
        throw std::invalid_argument("invalid account block session");
    m_sessionId = sessionId;
    m_actorAccountId = actorAccountId;
    m_pending.clear();
}

void V2WindowsAccountBlockProtocolClient::clearSession() {
    m_sessionId.clear();
    m_actorAccountId.clear();
    m_pending.clear();
}

V2WindowsAccountBlockProtocolClient::Command
V2WindowsAccountBlockProtocolClient::setAccountBlock(
        const std::string &targetAccountId, bool blocked,
        const std::string &clientOperationId) {
    if (m_sessionId.empty() || !canonicalUuid(targetAccountId)
            || targetAccountId == m_actorAccountId
            || !canonicalUuid(clientOperationId)
            || m_pending.size() >= maximumPendingOperations)
        throw std::invalid_argument("invalid account block request");
    const auto requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.count(requestId))
        throw std::runtime_error("invalid account block request id");
    const auto now = m_clock();
    if (now <= 0) throw std::runtime_error("account block clock must be positive");

    chat::v2::SetAccountBlock payload;
    payload.set_target_account_id(targetAccountId);
    payload.set_blocked(blocked);
    payload.set_client_operation_id(clientOperationId);
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(chat::v2::MESSAGE_TYPE_SET_ACCOUNT_BLOCK);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    envelope.set_client_message_id(clientOperationId);
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(serialize(payload));
    m_pending.emplace(requestId, Pending{targetAccountId, clientOperationId, blocked});
    return {requestId, clientOperationId, serialize(envelope)};
}

V2WindowsAccountBlockProtocolClient::Event
V2WindowsAccountBlockProtocolClient::receive(const std::string &bytes) {
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (m_sessionId.empty() || envelope.protocol_version() != 2
            || envelope.session_id() != m_sessionId || envelope.sent_at_epoch_ms() <= 0
            || !canonicalUuid(envelope.request_id()))
        throw std::runtime_error("invalid account block envelope");
    const auto position = m_pending.find(envelope.request_id());
    if (position == m_pending.end()
            || envelope.client_message_id() != position->second.clientOperationId)
        throw std::runtime_error("uncorrelated account block response");
    const Pending pending = position->second;

    Event result;
    result.requestId = envelope.request_id();
    result.clientOperationId = pending.clientOperationId;
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512 || !validUtf8(error.safe_message()))
            throw std::runtime_error("invalid account block error");
        result.retryable = error.retryable();
        m_pending.erase(position);
        return result;
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE
            || envelope.message_type() != chat::v2::MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED)
        throw std::runtime_error("account block response type confusion");
    const auto payload = parse<chat::v2::AccountBlockApplied>(envelope.payload());
    if (!canonicalUuid(payload.actor_account_id())
            || payload.actor_account_id() != m_actorAccountId
            || !canonicalUuid(payload.target_account_id())
            || payload.target_account_id() != pending.targetAccountId
            || payload.blocked() != pending.blocked
            || payload.client_operation_id() != pending.clientOperationId)
        throw std::runtime_error("invalid account block result");
    result.type = EventType::Applied;
    result.actorAccountId = payload.actor_account_id();
    result.targetAccountId = payload.target_account_id();
    result.blocked = payload.blocked();
    result.changed = payload.changed();
    m_pending.erase(position);
    return result;
}

void V2WindowsAccountBlockProtocolClient::abandon(const std::string &requestId) {
    m_pending.erase(requestId);
}

bool V2WindowsAccountBlockProtocolClient::canonicalUuid(const std::string &value) {
    if (value.size() != 36) return false;
    bool nonzero = false;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-') return false;
        } else if (!((value[index] >= '0' && value[index] <= '9')
                || (value[index] >= 'a' && value[index] <= 'f'))) {
            return false;
        } else {
            nonzero = nonzero || value[index] != '0';
        }
    }
    return nonzero;
}

bool V2WindowsAccountBlockProtocolClient::validUtf8(const std::string &value) {
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    while (position < value.size()) {
        const auto first = data[position++];
        if (first <= 0x7fU) continue;
        const int trailing = first >= 0xc2U && first <= 0xdfU ? 1
            : first >= 0xe0U && first <= 0xefU ? 2
            : first >= 0xf0U && first <= 0xf4U ? 3 : -1;
        if (trailing < 0 || position + static_cast<std::size_t>(trailing) > value.size())
            return false;
        std::uint32_t codepoint = first & (trailing == 1 ? 0x1fU : trailing == 2 ? 0x0fU : 0x07U);
        for (int index = 0; index < trailing; ++index) {
            const auto next = data[position++];
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

std::string V2WindowsAccountBlockProtocolClient::randomUuid() {
    std::array<unsigned char, 16> value{};
    std::random_device random;
    for (auto &byte : value) byte = static_cast<unsigned char>(random());
    value[6] = (value[6] & 0x0fU) | 0x40U;
    value[8] = (value[8] & 0x3fU) | 0x80U;
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 4 || index == 6 || index == 8 || index == 10) result += '-';
        result += hex[value[index] >> 4U];
        result += hex[value[index] & 0x0fU];
    }
    return result;
}
