#include "V2DeviceManagementProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <array>
#include <google/protobuf/message_lite.h>
#include <limits>
#include <random>
#include <stdexcept>
#include <unordered_set>
#include <utility>

namespace {
std::string bytes(const google::protobuf::MessageLite &message) {
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("protobuf encode failed");
    return encoded;
}

template <typename T>
T parse(const std::string &encoded) {
    constexpr std::size_t maximumDeviceFrameBytes = 256U * 1024U;
    if (encoded.size() > maximumDeviceFrameBytes
            || encoded.size() > static_cast<std::size_t>(std::numeric_limits<int>::max()))
        throw std::runtime_error("protobuf frame exceeds device-management bound");
    T value;
    if (!value.ParseFromArray(encoded.data(), static_cast<int>(encoded.size())))
        throw std::runtime_error("protobuf decode failed");
    return value;
}
}

V2DeviceManagementProtocolClient::V2DeviceManagementProtocolClient(RequestIdFactory factory)
    : m_factory(factory ? std::move(factory) : randomUuid) {}

void V2DeviceManagementProtocolClient::bindSession(
        const std::string &sessionId, const std::string &currentDeviceId) {
    if (!canonicalUuid(sessionId) || !canonicalUuid(currentDeviceId))
        throw std::invalid_argument("invalid authenticated session");
    m_sessionId = sessionId;
    m_currentDeviceId = currentDeviceId;
    m_pending.clear();
}

void V2DeviceManagementProtocolClient::clearSession() {
    m_sessionId.clear();
    m_currentDeviceId.clear();
    m_pending.clear();
}

std::size_t V2DeviceManagementProtocolClient::pendingCount() const {
    return m_pending.size();
}

V2DeviceManagementProtocolClient::Command
V2DeviceManagementProtocolClient::listDevices() {
    chat::v2::ListDevices payload;
    return command(chat::v2::MESSAGE_TYPE_LIST_DEVICES, bytes(payload),
                   {PendingType::List, {}});
}

V2DeviceManagementProtocolClient::Command
V2DeviceManagementProtocolClient::revokeDevice(const std::string &targetDeviceId) {
    if (!canonicalUuid(targetDeviceId) || targetDeviceId == m_currentDeviceId)
        throw std::invalid_argument("invalid revocation target");
    chat::v2::RevokeDevice payload;
    payload.set_target_device_id(targetDeviceId);
    return command(chat::v2::MESSAGE_TYPE_REVOKE_DEVICE, bytes(payload),
                   {PendingType::Revoke, targetDeviceId});
}

V2DeviceManagementProtocolClient::Command
V2DeviceManagementProtocolClient::command(
        int messageType, const std::string &payload, Pending pending) {
    if (m_sessionId.empty()) throw std::logic_error("not authenticated");
    if (m_pending.size() >= 16) throw std::runtime_error("too many pending requests");
    const std::string requestId = m_factory();
    if (!canonicalUuid(requestId) || m_pending.find(requestId) != m_pending.end())
        throw std::runtime_error("invalid request id");
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    envelope.set_message_type(messageType);
    envelope.set_request_id(requestId);
    envelope.set_session_id(m_sessionId);
    envelope.set_payload(payload);
    m_pending.emplace(requestId, std::move(pending));
    return {requestId, bytes(envelope)};
}

V2DeviceManagementProtocolClient::Event
V2DeviceManagementProtocolClient::receive(const std::string &encoded) {
    const auto envelope = parse<chat::v2::Envelope>(encoded);
    const auto pendingPosition = m_pending.find(envelope.request_id());
    if (envelope.protocol_version() != 2 || envelope.session_id() != m_sessionId
            || pendingPosition == m_pending.end())
        throw std::runtime_error("uncorrelated response");
    const Pending pending = pendingPosition->second;
    if (envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR
            && envelope.kind() == chat::v2::MESSAGE_KIND_ERROR) {
        const auto error = parse<chat::v2::ProtocolError>(envelope.payload());
        if (error.safe_message().size() > 512) throw std::runtime_error("invalid protocol error");
        m_pending.erase(pendingPosition);
        return {EventType::ProtocolError, envelope.request_id(), {}, {}};
    }
    if (pending.type == PendingType::List
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE) {
        const auto directory = parse<chat::v2::DeviceDirectory>(envelope.payload());
        if (directory.devices_size() < 1 || directory.devices_size() > 100)
            throw std::runtime_error("invalid device directory");
        std::vector<Device> devices;
        devices.reserve(static_cast<std::size_t>(directory.devices_size()));
        std::unordered_set<std::string> identifiers;
        int current = 0;
        for (const auto &value : directory.devices()) {
            const auto &id = value.device_id();
            if (!canonicalUuid(id) || !identifiers.emplace(id).second
                    || value.created_at_epoch_ms() <= 0
                    || value.last_seen_at_epoch_ms() < value.created_at_epoch_ms()
                    || (value.platform() != chat::v2::CLIENT_PLATFORM_WEB
                        && value.platform() != chat::v2::CLIENT_PLATFORM_WINDOWS))
                throw std::runtime_error("invalid device directory");
            if (value.current()) {
                ++current;
                if (id != m_currentDeviceId) throw std::runtime_error("wrong current device");
            }
            devices.push_back({
                id,
                value.platform() == chat::v2::CLIENT_PLATFORM_WINDOWS
                    ? Platform::Windows : Platform::Web,
                value.created_at_epoch_ms(),
                value.last_seen_at_epoch_ms(),
                value.current()});
        }
        if (current != 1) throw std::runtime_error("invalid current device count");
        m_pending.erase(pendingPosition);
        return {EventType::Directory, envelope.request_id(), std::move(devices), {}};
    }
    if (pending.type == PendingType::Revoke
            && envelope.message_type() == chat::v2::MESSAGE_TYPE_DEVICE_REVOKED
            && envelope.kind() == chat::v2::MESSAGE_KIND_RESPONSE) {
        const auto revoked = parse<chat::v2::DeviceRevoked>(envelope.payload());
        if (revoked.target_device_id() != pending.target || revoked.revoked_at_epoch_ms() <= 0)
            throw std::runtime_error("invalid revocation response");
        m_pending.erase(pendingPosition);
        return {EventType::Revoked, envelope.request_id(), {}, revoked.target_device_id()};
    }
    throw std::runtime_error("response type confusion");
}

bool V2DeviceManagementProtocolClient::canonicalUuid(const std::string &value) {
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

std::string V2DeviceManagementProtocolClient::randomUuid() {
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
