#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>

class V2WindowsAccountBlockProtocolClient final {
public:
    struct Command {
        std::string requestId;
        std::string clientOperationId;
        std::string bytes;
    };
    enum class EventType { Applied, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::string actorAccountId;
        std::string targetAccountId;
        std::string clientOperationId;
        bool blocked = false;
        bool changed = false;
        bool retryable = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsAccountBlockProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId, const std::string &actorAccountId);
    void clearSession();
    Command setAccountBlock(const std::string &targetAccountId, bool blocked,
                            const std::string &clientOperationId);
    Event receive(const std::string &bytes);
    void abandon(const std::string &requestId);
    std::size_t pendingCount() const { return m_pending.size(); }

private:
    struct Pending {
        std::string targetAccountId;
        std::string clientOperationId;
        bool blocked = false;
    };
    static bool canonicalUuid(const std::string &value);
    static bool validUtf8(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::string m_actorAccountId;
    std::unordered_map<std::string, Pending> m_pending;
};
