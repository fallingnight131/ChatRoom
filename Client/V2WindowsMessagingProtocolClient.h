#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsMessagingProtocolClient final {
public:
    struct ReplyReference {
        std::string targetMessageId;
        std::uint64_t targetConversationSequence = 0;
        std::string targetSenderAccountId;
    };
    struct Message {
        std::string conversationId;
        std::string messageId;
        std::uint64_t conversationSequence = 0;
        std::string senderAccountId;
        std::string senderDeviceId;
        std::string clientMessageId;
        std::string text;
        std::int64_t acceptedAtEpochMs = 0;
        bool hasReply = false;
        ReplyReference reply;
    };
    enum class ReactionKind { Like = 1, Love, Laugh, Surprised, Sad, Angry };
    struct ReactionChange {
        std::string conversationId;
        std::uint64_t conversationSequence = 0;
        std::string messageId;
        ReactionKind reaction = ReactionKind::Like;
        bool active = false;
        std::string actorAccountId;
        std::string clientOperationId;
        std::int64_t occurredAtEpochMs = 0;
    };
    struct Command {
        std::string requestId;
        std::string clientMessageId;
        std::string bytes;
    };
    enum class EventType {
        Accepted, HistoryPage, Published, ReactionApplied, ReactionChanged,
        ProtocolError
    };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::string clientMessageId;
        std::string conversationId;
        std::string messageId;
        std::uint64_t conversationSequence = 0;
        std::int64_t acceptedAtEpochMs = 0;
        bool duplicate = false;
        bool retryable = false;
        std::vector<Message> messages;
        std::vector<std::string> recalledMessageIds;
        std::vector<std::string> deletedMessageIds;
        std::vector<ReactionChange> reactionChanges;
        ReactionChange reactionChange;
        std::uint64_t nextSequence = 0;
        std::uint64_t latestSequence = 0;
        bool hasMore = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsMessagingProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command submitText(const std::string &conversationId,
                       const std::string &clientMessageId,
                       const std::string &text);
    Command submitReplyText(const std::string &conversationId,
                            const std::string &clientMessageId,
                            const std::string &targetMessageId,
                            const std::string &text);
    Command readHistory(const std::string &conversationId,
                        std::uint64_t afterSequence,
                        std::uint32_t limit);
    Command setReaction(const std::string &conversationId,
                        const std::string &messageId, ReactionKind reaction,
                        bool active, const std::string &clientOperationId);
    Event receive(const std::string &bytes);
    std::size_t pendingCount() const;

private:
    enum class PendingType { Submit, Reply, History, Reaction };
    struct Pending {
        PendingType type = PendingType::Submit;
        std::string conversationId;
        std::string clientMessageId;
        std::uint64_t afterSequence = 0;
        std::string messageId;
        ReactionKind reaction = ReactionKind::Like;
        bool active = false;
        std::string clientOperationId;
    };
    Command command(int messageType, const std::string &payload,
                    const std::string &clientMessageId, Pending pending);
    static bool canonicalUuid(const std::string &value);
    static bool boundedIdentifier(const std::string &value, bool required);
    static bool validUtf8(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::unordered_map<std::string, Pending> m_pending;
};
