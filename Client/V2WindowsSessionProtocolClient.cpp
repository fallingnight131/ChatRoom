#include "V2WindowsSessionProtocolClient.h"

#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <google/protobuf/message_lite.h>
#include <random>
#include <stdexcept>
#include <utility>

namespace {
constexpr std::size_t maximumWireBytes = 1024U * 1024U + 1024U;

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("protobuf encode failed");
    return encoded;
}

template <typename Message>
Message parse(const std::string &bytes) {
    if (bytes.size() > maximumWireBytes) throw std::runtime_error("V2 frame exceeds bound");
    Message message;
    if (!message.ParseFromArray(bytes.data(), static_cast<int>(bytes.size())))
        throw std::runtime_error("invalid V2 protobuf");
    return message;
}

void erase(std::vector<unsigned char> &value) {
    std::fill(value.begin(), value.end(), 0U);
    value.clear();
}

void erase(std::string &value) {
    std::fill(value.begin(), value.end(), '\0');
    value.clear();
}

struct VectorEraser final {
    std::vector<unsigned char> &value;
    ~VectorEraser() { erase(value); }
};

struct StringEraser final {
    std::string &value;
    ~StringEraser() { erase(value); }
};
}

V2WindowsSessionProtocolClient::V2WindowsSessionProtocolClient(
        std::string appVersion,
        std::string clientDeviceId,
        RequestIdFactory requestIdFactory,
        Clock clock,
        bool enableMessageForwarding,
        bool enableMessageSearch,
        bool enableAccountBlocking)
    : m_appVersion(std::move(appVersion)),
      m_clientDeviceId(std::move(clientDeviceId)),
      m_requestIdFactory(requestIdFactory ? std::move(requestIdFactory) : randomUuid),
      m_clock(clock ? std::move(clock) : systemTimeMs),
      m_devices(m_requestIdFactory, m_clock),
      m_messageForwardingEnabled(enableMessageForwarding),
      m_messageSearchEnabled(enableMessageSearch),
      m_accountBlockingEnabled(enableAccountBlocking) {
    if (!boundedText(m_appVersion, 64) || !canonicalUuid(m_clientDeviceId))
        throw std::invalid_argument("invalid Windows V2 client identity");
}

V2WindowsSessionProtocolClient::~V2WindowsSessionProtocolClient() {
    close();
}

V2WindowsSessionProtocolClient::State V2WindowsSessionProtocolClient::state() const {
    return m_state;
}

const V2WindowsSessionProtocolClient::Session *
V2WindowsSessionProtocolClient::session() const {
    return m_state == State::Authenticated ? &m_session : nullptr;
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::createClientHello() {
    requireState(State::New);
    chat::v2::ClientHello hello;
    hello.set_minimum_protocol_version(2);
    hello.set_maximum_protocol_version(2);
    hello.set_platform(chat::v2::CLIENT_PLATFORM_WINDOWS);
    hello.set_app_version(m_appVersion);
    hello.set_client_device_id(m_clientDeviceId);
    hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS);
    hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS);
    hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS);
    if (m_messageForwardingEnabled)
        hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_FORWARDING);
    if (m_messageSearchEnabled)
        hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_SEARCH);
    if (m_accountBlockingEnabled)
        hello.add_capabilities(chat::v2::CLIENT_CAPABILITY_ACCOUNT_BLOCKING);
    Command result = command(
        chat::v2::MESSAGE_TYPE_CLIENT_HELLO, serialize(hello),
        chat::v2::MESSAGE_TYPE_SERVER_HELLO);
    m_state = State::HelloSent;
    return result;
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::authenticate(
        std::string username, std::vector<unsigned char> passwordUtf8) {
    VectorEraser passwordEraser{passwordUtf8};
    requireState(State::Negotiated);
    if (!boundedText(username, 128) || passwordUtf8.empty() || passwordUtf8.size() > 1024
            || !validUtf8(passwordUtf8.data(), passwordUtf8.size())) {
        throw std::invalid_argument("invalid authentication payload");
    }
    chat::v2::Authenticate authenticate;
    authenticate.set_username(std::move(username));
    authenticate.set_password_utf8(passwordUtf8.data(), passwordUtf8.size());
    StringEraser protobufPasswordEraser{*authenticate.mutable_password_utf8()};
    std::string payload = serialize(authenticate);
    StringEraser payloadEraser{payload};
    Command result = command(chat::v2::MESSAGE_TYPE_AUTHENTICATE, payload,
                             chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED);
    m_state = State::AuthenticationSent;
    return result;
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::resumeSession(
        const std::string &sessionId, std::vector<unsigned char> resumeToken) {
    VectorEraser resumeTokenEraser{resumeToken};
    requireState(State::Negotiated);
    if (!canonicalUuid(sessionId) || resumeToken.size() != 32) {
        throw std::invalid_argument("invalid session resume payload");
    }
    chat::v2::ResumeSession resume;
    resume.set_session_id(sessionId);
    resume.set_resume_token(resumeToken.data(), resumeToken.size());
    StringEraser protobufTokenEraser{*resume.mutable_resume_token()};
    std::string payload = serialize(resume);
    StringEraser payloadEraser{payload};
    Command result = command(chat::v2::MESSAGE_TYPE_RESUME_SESSION, payload,
                             chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED);
    m_state = State::AuthenticationSent;
    return result;
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::listDevices() {
    requireState(State::Authenticated);
    return m_devices.listDevices();
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::revokeDevice(const std::string &targetDeviceId) {
    requireState(State::Authenticated);
    return m_devices.revokeDevice(targetDeviceId);
}

V2WindowsSessionProtocolClient::Event
V2WindowsSessionProtocolClient::receive(const std::string &bytes) {
    if (m_state == State::Closed) throw std::logic_error("V2 protocol client is closed");
    if (bytes.size() > m_maximumFrameBytes) throw std::runtime_error("negotiated frame exceeded");
    const auto envelope = parse<chat::v2::Envelope>(bytes);
    if (envelope.protocol_version() != 2 || envelope.sent_at_epoch_ms() <= 0)
        throw std::runtime_error("invalid V2 response envelope");

    if (m_state == State::Authenticated) {
        if (envelope.session_id() != m_session.sessionId)
            throw std::runtime_error("authenticated session mismatch");
        const auto event = m_devices.receive(bytes);
        return {
            event.type == V2DeviceManagementProtocolClient::EventType::Directory
                ? EventType::DeviceDirectory
                : event.type == V2DeviceManagementProtocolClient::EventType::Revoked
                    ? EventType::DeviceRevoked : EventType::ProtocolError,
            event.requestId, {}, event, 0};
    }

    if (envelope.request_id() != m_pendingRequestId || m_pendingRequestId.empty())
        throw std::runtime_error("uncorrelated V2 response");
    if (envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR) {
        if (envelope.kind() != chat::v2::MESSAGE_KIND_ERROR)
            throw std::runtime_error("invalid protocol error kind");
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.safe_message().size() > 512)
            throw std::runtime_error("invalid protocol error");
        const std::string requestId = std::move(m_pendingRequestId);
        m_expectedType = 0;
        m_state = State::Closed;
        return {EventType::ProtocolError, requestId, {}, {}, 0};
    }
    if (envelope.message_type() == chat::v2::MESSAGE_TYPE_AUTHENTICATION_REJECTED) {
        if (m_state != State::AuthenticationSent
                || envelope.kind() != chat::v2::MESSAGE_KIND_ERROR)
            throw std::runtime_error("unexpected authentication rejection");
        const auto rejected = parse<chat::v2::AuthenticationRejected>(envelope.payload());
        if ((rejected.reason() != chat::v2::AUTHENTICATION_REJECTION_REASON_REJECTED
             && rejected.reason() != chat::v2::AUTHENTICATION_REJECTION_REASON_RATE_LIMITED)
                || rejected.retry_after_ms() < 0)
            throw std::runtime_error("invalid authentication rejection");
        const std::string requestId = std::move(m_pendingRequestId);
        m_expectedType = 0;
        m_state = State::Closed;
        return {EventType::AuthenticationRejected, requestId, {}, {},
                rejected.retry_after_ms()};
    }
    if (envelope.kind() != chat::v2::MESSAGE_KIND_RESPONSE
            || envelope.message_type() != m_expectedType)
        throw std::runtime_error("V2 response type confusion");

    const std::string requestId = std::move(m_pendingRequestId);
    m_expectedType = 0;
    if (envelope.message_type() == chat::v2::MESSAGE_TYPE_SERVER_HELLO) {
        requireState(State::HelloSent);
        if (!envelope.session_id().empty()) throw std::runtime_error("hello carried session");
        const auto hello = parse<chat::v2::ServerHello>(envelope.payload());
        std::vector<chat::v2::ClientCapability> expectedCapabilities{
            chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS,
            chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS,
            chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS,
            chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS};
        if (m_messageForwardingEnabled)
            expectedCapabilities.push_back(chat::v2::CLIENT_CAPABILITY_MESSAGE_FORWARDING);
        if (m_messageSearchEnabled)
            expectedCapabilities.push_back(chat::v2::CLIENT_CAPABILITY_MESSAGE_SEARCH);
        if (m_accountBlockingEnabled)
            expectedCapabilities.push_back(chat::v2::CLIENT_CAPABILITY_ACCOUNT_BLOCKING);
        if (hello.selected_protocol_version() != 2
                || !boundedText(hello.connection_id(), 128)
                || hello.server_time_epoch_ms() <= 0
                || hello.maximum_frame_bytes() < 1
                || hello.maximum_frame_bytes() > maximumWireBytes
                || hello.enabled_capabilities_size()
                    != static_cast<int>(expectedCapabilities.size())
                || !std::equal(expectedCapabilities.begin(), expectedCapabilities.end(),
                    hello.enabled_capabilities().begin()))
            throw std::runtime_error("invalid server hello");
        m_maximumFrameBytes = hello.maximum_frame_bytes();
        m_state = State::Negotiated;
        return {EventType::ServerHello, requestId, {}, {}, 0};
    }

    requireState(State::AuthenticationSent);
    const auto established = parse<chat::v2::SessionEstablished>(envelope.payload());
    if (!canonicalUuid(established.account_id())
            || !canonicalUuid(established.device_id())
            || established.device_id() != m_clientDeviceId
            || !canonicalUuid(established.session_id())
            || established.session_id() != envelope.session_id()
            || established.resume_token().size() != 32
            || established.expires_at_epoch_ms() <= 0
            || !boundedText(established.display_name(), 400))
        throw std::runtime_error("invalid established session");
    m_session = {
        established.account_id(), established.device_id(), established.session_id(),
        std::vector<unsigned char>(established.resume_token().begin(),
                                   established.resume_token().end()),
        established.expires_at_epoch_ms(), established.display_name()};
    m_devices.bindSession(m_session.sessionId, m_session.deviceId);
    m_state = State::Authenticated;
    Session observable{
        m_session.accountId, m_session.deviceId, m_session.sessionId, {},
        m_session.expiresAtEpochMs, m_session.displayName};
    return {EventType::SessionEstablished, requestId, std::move(observable), {}, 0};
}

void V2WindowsSessionProtocolClient::close() {
    erase(m_session.resumeToken);
    m_session = {};
    m_devices.clearSession();
    m_pendingRequestId.clear();
    m_expectedType = 0;
    m_state = State::Closed;
}

V2WindowsSessionProtocolClient::Command
V2WindowsSessionProtocolClient::command(
        std::uint32_t messageType,
        const std::string &payload,
        std::uint32_t expectedType) {
    if (!m_pendingRequestId.empty()) throw std::logic_error("V2 request already pending");
    const std::string requestId = m_requestIdFactory();
    if (!canonicalUuid(requestId)) throw std::runtime_error("invalid request id");
    const std::int64_t now = m_clock();
    if (now <= 0) throw std::runtime_error("clock must be positive");
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(messageType);
    envelope.set_request_id(requestId);
    envelope.set_sent_at_epoch_ms(now);
    envelope.set_payload(payload);
    const std::string encoded = serialize(envelope);
    if (encoded.size() > m_maximumFrameBytes) throw std::runtime_error("V2 frame exceeds bound");
    m_pendingRequestId = requestId;
    m_expectedType = expectedType;
    return {requestId, encoded};
}

void V2WindowsSessionProtocolClient::requireState(State expected) const {
    if (m_state != expected) throw std::logic_error("unexpected Windows V2 protocol state");
}

bool V2WindowsSessionProtocolClient::canonicalUuid(const std::string &value) {
    if (value.size() != 36) return false;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-') return false;
            continue;
        }
        if (!((value[index] >= '0' && value[index] <= '9')
              || (value[index] >= 'a' && value[index] <= 'f')))
            return false;
    }
    return value[14] >= '1' && value[14] <= '5'
        && (value[19] == '8' || value[19] == '9'
            || value[19] == 'a' || value[19] == 'b');
}

bool V2WindowsSessionProtocolClient::boundedText(
        const std::string &value, std::size_t maximumBytes) {
    return !value.empty() && value.size() <= maximumBytes
        && validUtf8(reinterpret_cast<const unsigned char *>(value.data()), value.size());
}

bool V2WindowsSessionProtocolClient::validUtf8(
        const unsigned char *value, std::size_t size) {
    std::size_t index = 0;
    const auto continuation = [](unsigned char byte) {
        return byte >= 0x80U && byte <= 0xbfU;
    };
    while (index < size) {
        const unsigned char first = value[index++];
        if (first <= 0x7fU) continue;
        if (first >= 0xc2U && first <= 0xdfU) {
            if (index >= size || !continuation(value[index++])) return false;
            continue;
        }
        if (first >= 0xe0U && first <= 0xefU) {
            if (index + 1 >= size) return false;
            const unsigned char second = value[index++];
            const unsigned char third = value[index++];
            if (!continuation(third)
                    || (first == 0xe0U && (second < 0xa0U || second > 0xbfU))
                    || (first == 0xedU && (second < 0x80U || second > 0x9fU))
                    || ((first != 0xe0U && first != 0xedU) && !continuation(second)))
                return false;
            continue;
        }
        if (first >= 0xf0U && first <= 0xf4U) {
            if (index + 2 >= size) return false;
            const unsigned char second = value[index++];
            const unsigned char third = value[index++];
            const unsigned char fourth = value[index++];
            if (!continuation(third) || !continuation(fourth)
                    || (first == 0xf0U && (second < 0x90U || second > 0xbfU))
                    || (first == 0xf4U && (second < 0x80U || second > 0x8fU))
                    || ((first != 0xf0U && first != 0xf4U) && !continuation(second)))
                return false;
            continue;
        }
        return false;
    }
    return true;
}

std::string V2WindowsSessionProtocolClient::randomUuid() {
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

std::int64_t V2WindowsSessionProtocolClient::systemTimeMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
