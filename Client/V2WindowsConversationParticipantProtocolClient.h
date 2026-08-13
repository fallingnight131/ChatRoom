#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsConversationParticipantProtocolClient final {
public:
    enum class Role { Owner, Admin, Member };
    struct Participant {
        std::string accountId;
        std::string displayName;
        Role role = Role::Member;
    };
    struct Command { std::string requestId; std::string bytes; };
    enum class EventType { Page, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::string conversationId;
        std::vector<Participant> participants;
        std::string nextAccountId;
        bool hasMore = false;
        bool retryable = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsConversationParticipantProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command list(const std::string &conversationId, std::uint32_t limit,
                 const std::string &afterAccountId = {});
    Event receive(const std::string &bytes);
    std::size_t pendingCount() const { return m_pending.size(); }

private:
    struct Pending { std::string conversationId; std::string afterAccountId; };
    static bool canonicalUuid(const std::string &value);
    static bool validUtf8(const std::string &value);
    static bool blank(const std::string &value);
    static std::size_t scalarCount(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::unordered_map<std::string, Pending> m_pending;
};
