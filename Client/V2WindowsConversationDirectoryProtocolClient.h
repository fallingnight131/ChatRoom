#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsConversationDirectoryProtocolClient final {
public:
    enum class Kind { Direct, Group };
    enum class Role { Owner, Admin, Member };
    struct Cursor {
        std::int64_t updatedAtEpochMs = 0;
        std::string conversationId;
    };
    struct Conversation {
        std::string conversationId;
        Kind kind = Kind::Direct;
        std::string displayName;
        Role role = Role::Member;
        std::uint64_t latestSequence = 0;
        std::uint64_t lastReadSequence = 0;
        std::int64_t updatedAtEpochMs = 0;
    };
    struct Command { std::string requestId; std::string bytes; };
    enum class EventType { Page, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::vector<Conversation> conversations;
        Cursor next;
        bool hasMore = false;
        bool retryable = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsConversationDirectoryProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command list(std::uint32_t limit);
    Command list(std::uint32_t limit, const Cursor &after);
    Event receive(const std::string &bytes);
    std::size_t pendingCount() const { return m_pending.size(); }

private:
    static bool canonicalUuid(const std::string &value);
    static bool validUtf8(const std::string &value);
    static bool onlyUnicodeWhitespace(const std::string &value);
    static std::size_t unicodeScalarCount(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::unordered_map<std::string, Cursor> m_pending;
};
