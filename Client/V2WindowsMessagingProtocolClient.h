#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsMessagingProtocolClient final {
public:
    struct Mention {
        std::string targetAccountId;
        std::uint32_t startUtf8Byte = 0;
        std::uint32_t lengthUtf8Bytes = 0;
    };
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
        std::uint32_t contentRevision = 0;
        std::int64_t editedAtEpochMs = 0;
        bool hasReply = false;
        ReplyReference reply;
        std::vector<Mention> mentions;
        bool forwarded = false;
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
    struct PinChange {
        std::string conversationId; std::uint64_t conversationSequence = 0;
        std::string messageId; bool pinned = false; std::string actorAccountId;
        std::string clientOperationId; std::int64_t occurredAtEpochMs = 0;
    };
    struct EditChange {
        std::string conversationId; std::uint64_t conversationSequence = 0;
        std::string messageId; std::uint32_t contentRevision = 0;
        std::string text; std::string actorAccountId; std::string clientOperationId;
        std::int64_t occurredAtEpochMs = 0;
        std::vector<Mention> mentions;
    };
    struct Command {
        std::string requestId;
        std::string clientMessageId;
        std::string bytes;
    };
    enum class EventType {
        Accepted, HistoryPage, Published, ReactionApplied, ReactionChanged, PinApplied, PinChanged,
        EditApplied, Edited, ProtocolError
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
        std::vector<PinChange> pinChanges;
        PinChange pinChange;
        std::vector<EditChange> editChanges;
        EditChange editChange;
        std::uint64_t nextSequence = 0;
        std::uint64_t latestSequence = 0;
        bool hasMore = false;
        int protocolErrorCode = 0;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsMessagingProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {}, bool enableForwarding = false);
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command submitText(const std::string &conversationId,
                       const std::string &clientMessageId,
                       const std::string &text,
                       const std::vector<Mention> &mentions = {});
    Command submitReplyText(const std::string &conversationId,
                            const std::string &clientMessageId,
                            const std::string &targetMessageId,
                            const std::string &text,
                            const std::vector<Mention> &mentions = {});
    Command forwardMessage(const std::string &sourceConversationId,
                           const std::string &sourceMessageId,
                           std::uint32_t expectedSourceContentRevision,
                           const std::string &targetConversationId,
                           const std::string &clientMessageId);
    Command readHistory(const std::string &conversationId,
                        std::uint64_t afterSequence,
                        std::uint32_t limit);
    Command setReaction(const std::string &conversationId,
                        const std::string &messageId, ReactionKind reaction,
                        bool active, const std::string &clientOperationId);
    Command setPin(const std::string &conversationId, const std::string &messageId,
                   bool pinned, const std::string &clientOperationId);
    Command editMessage(const std::string &conversationId, const std::string &messageId,
                        std::uint32_t expectedRevision, const std::string &text,
                        const std::string &clientOperationId,
                        const std::vector<Mention> &mentions = {});
    Event receive(const std::string &bytes);
    void abandon(const std::string &requestId);
    std::size_t pendingCount() const;

private:
    enum class PendingType { Submit, Reply, Forward, History, Reaction, Pin, Edit };
    struct Pending {
        PendingType type = PendingType::Submit;
        std::string conversationId;
        std::string clientMessageId;
        std::uint64_t afterSequence = 0;
        std::string messageId;
        ReactionKind reaction = ReactionKind::Like;
        bool active = false;
        bool pinned = false;
        std::string clientOperationId;
        std::uint32_t expectedRevision = 0;
        std::string text;
        std::vector<Mention> mentions;
    };
    Command command(int messageType, const std::string &payload,
                    const std::string &clientMessageId, Pending pending);
    static bool canonicalUuid(const std::string &value);
    static bool boundedIdentifier(const std::string &value, bool required);
    static bool validUtf8(const std::string &value);
    static bool validMentions(const std::string &text,
                              const std::vector<Mention> &mentions);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    bool m_enableForwarding = false;
    std::unordered_map<std::string, Pending> m_pending;
};
