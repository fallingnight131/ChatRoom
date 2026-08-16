#pragma once

#include "V2DeviceManagementProtocolClient.h"
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

class V2WindowsSessionProtocolClient final {
public:
    enum class State {
        New,
        HelloSent,
        Negotiated,
        AuthenticationSent,
        Authenticated,
        Closed
    };
    struct Session {
        std::string accountId;
        std::string deviceId;
        std::string sessionId;
        std::vector<unsigned char> resumeToken;
        std::int64_t expiresAtEpochMs = 0;
        std::string displayName;
    };
    enum class EventType {
        ServerHello,
        SessionEstablished,
        AuthenticationRejected,
        ProtocolError,
        DeviceDirectory,
        DeviceRevoked
    };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        Session session;
        V2DeviceManagementProtocolClient::Event device;
        std::int64_t retryAfterMs = 0;
    };
    using Command = V2DeviceManagementProtocolClient::Command;
    using RequestIdFactory = V2DeviceManagementProtocolClient::RequestIdFactory;
    using Clock = V2DeviceManagementProtocolClient::Clock;

    V2WindowsSessionProtocolClient(
        std::string appVersion,
        std::string clientDeviceId,
        RequestIdFactory requestIdFactory = {},
        Clock clock = {},
        bool enableMessageForwarding = false,
        bool enableMessageSearch = false,
        bool enableAccountBlocking = false);
    ~V2WindowsSessionProtocolClient();

    State state() const;
    const Session *session() const;
    Command createClientHello();
    Command authenticate(std::string username, std::vector<unsigned char> passwordUtf8);
    Command resumeSession(
        const std::string &sessionId, std::vector<unsigned char> resumeToken);
    Command listDevices();
    Command revokeDevice(const std::string &targetDeviceId);
    Event receive(const std::string &bytes);
    void close();

private:
    Command command(
        std::uint32_t messageType,
        const std::string &payload,
        std::uint32_t expectedType);
    void requireState(State expected) const;
    static bool canonicalUuid(const std::string &value);
    static bool boundedText(const std::string &value, std::size_t maximumBytes);
    static bool validUtf8(const unsigned char *value, std::size_t size);
    static std::string randomUuid();
    static std::int64_t systemTimeMs();

    std::string m_appVersion;
    std::string m_clientDeviceId;
    RequestIdFactory m_requestIdFactory;
    Clock m_clock;
    V2DeviceManagementProtocolClient m_devices;
    State m_state = State::New;
    std::string m_pendingRequestId;
    std::uint32_t m_expectedType = 0;
    std::size_t m_maximumFrameBytes = 1024U * 1024U + 1024U;
    bool m_messageForwardingEnabled = false;
    bool m_messageSearchEnabled = false;
    bool m_accountBlockingEnabled = false;
    Session m_session;
};
