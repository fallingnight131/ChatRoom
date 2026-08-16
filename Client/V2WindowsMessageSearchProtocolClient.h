#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsMessageSearchProtocolClient final {
public:
    struct Command { std::string requestId; std::string bytes; };
    struct Hit {
        std::string conversationId;
        std::string messageId;
        std::uint64_t conversationSequence = 0;
        std::string senderAccountId;
        std::string senderDeviceId;
        std::string clientMessageId;
        std::string text;
        std::int64_t acceptedAtEpochMs = 0;
        std::uint32_t contentRevision = 0;
        std::int64_t editedAtEpochMs = 0;
    };
    enum class EventType { Page, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::string conversationId;
        std::vector<Hit> hits;
        std::uint64_t nextBeforeSequence = 0;
        bool hasMore = false;
        bool retryable = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsMessageSearchProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {}, bool enableForwarding = false);
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command search(const std::string &conversationId, const std::string &literalQuery,
                   std::uint64_t beforeSequence, std::uint32_t limit);
    Event receive(const std::string &bytes);
    void abandon(const std::string &requestId);
    std::size_t pendingCount() const { return m_pending.size(); }

private:
    struct Pending { std::string conversationId; std::uint64_t beforeSequence = 0; };
    static bool canonicalUuid(const std::string &value);
    static bool validUtf8(const std::string &value);
    static bool stripped(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    bool m_enableForwarding = false;
    std::unordered_map<std::string, Pending> m_pending;
};
