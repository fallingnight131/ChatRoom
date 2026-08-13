#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2DeviceManagementProtocolClient final {
public:
    enum class Platform { Web, Windows };
    struct Device {
        std::string deviceId;
        Platform platform = Platform::Web;
        std::int64_t createdAtEpochMs = 0;
        std::int64_t lastSeenAtEpochMs = 0;
        bool current = false;
    };
    struct Command {
        std::string requestId;
        std::string bytes;
    };
    enum class EventType { Directory, Revoked, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::vector<Device> devices;
        std::string targetDeviceId;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2DeviceManagementProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId, const std::string &currentDeviceId);
    void clearSession();
    Command listDevices();
    Command revokeDevice(const std::string &targetDeviceId);
    Event receive(const std::string &bytes);
    std::size_t pendingCount() const;

private:
    enum class PendingType { List, Revoke };
    struct Pending {
        PendingType type = PendingType::List;
        std::string target;
    };
    Command command(int messageType, const std::string &payload, Pending pending);
    static bool canonicalUuid(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::string m_currentDeviceId;
    std::unordered_map<std::string, Pending> m_pending;
};
