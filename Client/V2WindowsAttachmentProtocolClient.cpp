#include "V2WindowsAttachmentProtocolClient.h"

#include "chat/v2/attachment.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <google/protobuf/message_lite.h>
#include <random>
#include <stdexcept>
#include <unordered_set>
#include <utility>

namespace {
constexpr std::size_t maximumWireBytes = 1024U * 1024U + 1024U;
constexpr std::uint64_t maximumAttachmentBytes = 10ULL * 1024ULL * 1024ULL * 1024ULL;

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result))
        throw std::runtime_error("attachment encode failed");
    return result;
}

template <typename Message> Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes)
        throw std::runtime_error("attachment frame too large");
    Message result;
    if (!result.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("attachment decode failed");
    return result;
}
}

V2WindowsAttachmentProtocolClient::V2WindowsAttachmentProtocolClient(
        RequestIdFactory factory, Clock clock)
    : m_factory(factory ? std::move(factory) : randomUuid),
      m_clock(clock ? std::move(clock) : [] {
          return std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::system_clock::now().time_since_epoch()).count();
      }) {}

void V2WindowsAttachmentProtocolClient::bindSession(const std::string &sessionId) {
    if (!canonicalUuid(sessionId))
        throw std::invalid_argument("invalid attachment session");
    m_sessionId = sessionId;
    m_pending.clear();
    m_attachments.clear();
}

void V2WindowsAttachmentProtocolClient::clearSession() {
    m_sessionId.clear();
    m_pending.clear();
    m_attachments.clear();
}

V2WindowsAttachmentProtocolClient::Command
V2WindowsAttachmentProtocolClient::registerAttachment(
        const std::string &conversationId, const std::string &clientAttachmentId,
        const std::string &fileName, const std::string &mediaType,
        std::uint64_t byteSize, const std::string &contentSha256) {
    if (!canonicalUuid(conversationId)
            || !validUtf8Text(clientAttachmentId, 128)
            || !validUtf8Text(fileName, 255) || fileName == "." || fileName == ".."
            || fileName.find('/') != std::string::npos
            || fileName.find('\\') != std::string::npos
            || !validMediaType(mediaType) || byteSize == 0
            || byteSize > maximumAttachmentBytes || contentSha256.size() != 32)
        throw std::invalid_argument("invalid attachment registration");
    chat::v2::RegisterAttachment payload;
    payload.set_conversation_id(conversationId);
    payload.set_client_attachment_id(clientAttachmentId);
    payload.set_file_name(fileName);
    payload.set_media_type(mediaType);
    payload.set_byte_size(byteSize);
    payload.set_content_sha256(contentSha256);
    return command(chat::v2::MESSAGE_TYPE_REGISTER_ATTACHMENT, serialize(payload),
        {PendingType::Register, conversationId, clientAttachmentId, {}});
}

V2WindowsAttachmentProtocolClient::Command
V2WindowsAttachmentProtocolClient::authorizeUpload(const std::string &attachmentId) {
    const auto tracked = m_attachments.find(attachmentId);
    if (tracked == m_attachments.end())
        throw std::invalid_argument("unknown attachment authorization");
    chat::v2::AuthorizeAttachmentUpload payload;
    payload.set_attachment_id(attachmentId);
    return command(chat::v2::MESSAGE_TYPE_AUTHORIZE_ATTACHMENT_UPLOAD, serialize(payload),
        {PendingType::Authorize, tracked->second, {}, attachmentId});
}

V2WindowsAttachmentProtocolClient::Command
V2WindowsAttachmentProtocolClient::completeUpload(const std::string &attachmentId) {
    const auto tracked = m_attachments.find(attachmentId);
    if (tracked == m_attachments.end())
        throw std::invalid_argument("unknown attachment completion");
    chat::v2::CompleteAttachmentUpload payload;
    payload.set_attachment_id(attachmentId);
    return command(chat::v2::MESSAGE_TYPE_COMPLETE_ATTACHMENT_UPLOAD, serialize(payload),
        {PendingType::Complete, tracked->second, {}, attachmentId});
}

V2WindowsAttachmentProtocolClient::Command
V2WindowsAttachmentProtocolClient::command(
        int messageType, const std::string &payload, Pending pending) {
    if (m_sessionId.empty() || m_pending.size() >= 8)
        throw std::invalid_argument("attachment protocol is not ready");
    const std::string requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.count(requestId))
        throw std::runtime_error("invalid attachment request id");
    const auto now = m_clock();
    if (now <= 0) throw std::runtime_error("attachment clock must be positive");
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(messageType);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(payload);
    m_pending.emplace(requestId, std::move(pending));
    return {requestId, serialize(envelope)};
}

V2WindowsAttachmentProtocolClient::Event
V2WindowsAttachmentProtocolClient::receive(const std::string &bytes) {
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (m_sessionId.empty() || envelope.protocol_version() != 2
            || envelope.session_id() != m_sessionId || envelope.sent_at_epoch_ms() <= 0
            || !envelope.client_message_id().empty()
            || !canonicalUuid(envelope.request_id()))
        throw std::runtime_error("invalid attachment envelope");
    const auto position = m_pending.find(envelope.request_id());
    if (position == m_pending.end())
        throw std::runtime_error("uncorrelated attachment response");
    const Pending pending = position->second;
    if (envelope.kind() == chat::v2::MESSAGE_KIND_ERROR
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.code() <= chat::v2::PROTOCOL_ERROR_CODE_UNSPECIFIED
                || error.code() > chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED
                || error.safe_message().size() > 512
                || (!error.safe_message().empty()
                    && !validUtf8Text(error.safe_message(), 512)))
            throw std::runtime_error("invalid attachment error");
        m_pending.erase(position);
        Event result;
        result.requestId = envelope.request_id();
        result.retryable = error.retryable();
        return result;
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE)
        throw std::runtime_error("attachment response type confusion");
    Event result;
    result.requestId = envelope.request_id();
    if (pending.type == PendingType::Register
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_ATTACHMENT_REGISTERED) {
        const auto payload = parse<chat::v2::AttachmentRegistered>(envelope.payload());
        if (!canonicalUuid(payload.attachment_id())
                || payload.conversation_id() != pending.conversationId
                || payload.client_attachment_id() != pending.clientAttachmentId
                || m_attachments.size() >= 8)
            throw std::runtime_error("invalid attachment registration response");
        result.type = EventType::Registered;
        result.attachmentId = payload.attachment_id();
        result.conversationId = payload.conversation_id();
        result.clientAttachmentId = payload.client_attachment_id();
        result.duplicate = payload.duplicate();
        m_attachments[result.attachmentId] = result.conversationId;
    } else if (pending.type == PendingType::Authorize
            && envelope.message_type()
                == chat::v2::MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED) {
        const auto payload = parse<chat::v2::AttachmentUploadAuthorized>(envelope.payload());
        if (payload.attachment_id() != pending.attachmentId
                || !validUploadUri(payload.upload_uri())
                || payload.expires_at_epoch_ms() <= 0
                || payload.required_headers_size() < 1
                || payload.required_headers_size() > 32)
            throw std::runtime_error("invalid attachment upload grant");
        std::unordered_set<std::string> names;
        for (const auto &header : payload.required_headers()) {
            if (!validUtf8Text(header.name(), 128)
                    || !validUtf8Text(header.value(), 4096)
                    || !std::all_of(header.name().begin(), header.name().end(), [](char value) {
                        return value < 'A' || value > 'Z';
                    }) || header.name() == "host" || header.name() == "content-length"
                    || !names.insert(header.name()).second)
                throw std::runtime_error("invalid attachment upload header");
            result.requiredHeaders.push_back({header.name(), header.value()});
        }
        result.type = EventType::UploadAuthorized;
        result.attachmentId = payload.attachment_id();
        result.conversationId = pending.conversationId;
        result.uploadUri = payload.upload_uri();
        result.expiresAtEpochMs = payload.expires_at_epoch_ms();
    } else if (pending.type == PendingType::Complete
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_ATTACHMENT_READY) {
        const auto payload = parse<chat::v2::AttachmentReady>(envelope.payload());
        if (payload.attachment_id() != pending.attachmentId
                || payload.conversation_id() != pending.conversationId
                || payload.ready_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid attachment ready response");
        result.type = EventType::Ready;
        result.attachmentId = payload.attachment_id();
        result.conversationId = payload.conversation_id();
        result.duplicate = payload.duplicate();
        result.readyAtEpochMs = payload.ready_at_epoch_ms();
        m_attachments.erase(result.attachmentId);
    } else {
        throw std::runtime_error("attachment response type confusion");
    }
    m_pending.erase(position);
    return result;
}

void V2WindowsAttachmentProtocolClient::abandon(const std::string &requestId) {
    m_pending.erase(requestId);
}

bool V2WindowsAttachmentProtocolClient::canonicalUuid(const std::string &value) {
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

bool V2WindowsAttachmentProtocolClient::validUtf8Text(
        const std::string &value, std::size_t maximumBytes) {
    if (value.empty() || value.size() > maximumBytes) return false;
    const auto *data = reinterpret_cast<const unsigned char *>(value.data());
    std::size_t position = 0;
    bool nonWhitespace = false;
    while (position < value.size()) {
        const auto first = data[position++];
        std::uint32_t codepoint = first;
        int trailing = 0;
        if (first >= 0xc2U && first <= 0xdfU) { trailing = 1; codepoint = first & 0x1fU; }
        else if (first >= 0xe0U && first <= 0xefU) { trailing = 2; codepoint = first & 0x0fU; }
        else if (first >= 0xf0U && first <= 0xf4U) { trailing = 3; codepoint = first & 0x07U; }
        else if (first > 0x7fU) return false;
        if (position + static_cast<std::size_t>(trailing) > value.size()) return false;
        for (int index = 0; index < trailing; ++index) {
            const auto next = data[position++];
            if ((next & 0xc0U) != 0x80U) return false;
            codepoint = (codepoint << 6U) | (next & 0x3fU);
        }
        if ((trailing == 2 && codepoint < 0x800U)
                || (trailing == 3 && codepoint < 0x10000U)
                || (codepoint >= 0xd800U && codepoint <= 0xdfffU)
                || codepoint > 0x10ffffU
                || codepoint <= 0x1fU || (codepoint >= 0x7fU && codepoint <= 0x9fU))
            return false;
        const bool whitespace = codepoint == 0x20U || codepoint == 0xa0U
            || codepoint == 0x1680U || (codepoint >= 0x2000U && codepoint <= 0x200aU)
            || codepoint == 0x2028U || codepoint == 0x2029U
            || codepoint == 0x202fU || codepoint == 0x205fU || codepoint == 0x3000U;
        nonWhitespace = nonWhitespace || !whitespace;
    }
    return nonWhitespace;
}

bool V2WindowsAttachmentProtocolClient::validMediaType(const std::string &value) {
    const auto slash = value.find('/');
    if (slash == std::string::npos || slash == 0 || slash + 1 >= value.size()
            || value.find('/', slash + 1) != std::string::npos || value.size() > 127)
        return false;
    const auto alphanumeric = [](unsigned char character) {
        return (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9');
    };
    if (!alphanumeric(static_cast<unsigned char>(value.front()))
            || !alphanumeric(static_cast<unsigned char>(value[slash + 1])))
        return false;
    const auto valid = [](unsigned char character) {
        return (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9')
            || character == '!' || character == '#' || character == '$'
            || character == '&' || character == '^' || character == '_'
            || character == '.' || character == '+' || character == '-';
    };
    return std::all_of(value.begin(), value.end(), [&](char character) {
        return character == '/' || valid(static_cast<unsigned char>(character));
    });
}

bool V2WindowsAttachmentProtocolClient::validUploadUri(const std::string &value) {
    if (value.size() < 10 || value.size() > 8192
            || value.rfind("https://", 0) != 0 || value.find('#') != std::string::npos)
        return false;
    const auto authorityEnd = value.find_first_of("/?", 8);
    const auto authority = value.substr(8, authorityEnd == std::string::npos
        ? std::string::npos : authorityEnd - 8);
    if (authority.empty() || authority.find('@') != std::string::npos)
        return false;
    std::string host = authority;
    std::string port;
    if (authority.front() == '[') {
        const auto closing = authority.find(']');
        if (closing == std::string::npos || closing == 1) return false;
        host = authority.substr(1, closing - 1);
        if (closing + 1 < authority.size()) {
            if (authority[closing + 1] != ':') return false;
            port = authority.substr(closing + 2);
        }
    } else {
        const auto colon = authority.rfind(':');
        if (colon != std::string::npos) {
            if (authority.find(':') != colon) return false;
            host = authority.substr(0, colon);
            port = authority.substr(colon + 1);
        }
        if (host.empty() || !std::all_of(host.begin(), host.end(), [](unsigned char character) {
                return (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '-';
            })) return false;
    }
    if (!port.empty() && !std::all_of(port.begin(), port.end(), [](unsigned char character) {
            return character >= '0' && character <= '9';
        })) return false;
    return !host.empty()
        && std::none_of(value.begin(), value.end(), [](unsigned char character) {
            return character <= 0x20U || character == 0x7fU;
        });
}

std::string V2WindowsAttachmentProtocolClient::randomUuid() {
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
