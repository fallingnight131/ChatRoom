#pragma once

#include <QList>
#include <QSqlDatabase>
#include <QString>
#include <QStringList>

class V2LocalMessageRepository final {
public:
    enum class DeliveryState { Pending, Failed, Accepted };
    enum class EditDeliveryState { Pending, Failed, Conflict };
    enum class ReactionKind { Like = 1, Love, Laugh, Surprised, Sad, Angry };
    struct Mention {
        QString targetAccountId;
        int startUtf8Byte = 0;
        int lengthUtf8Bytes = 0;
    };
    struct ReactionAggregate {
        ReactionKind reaction = ReactionKind::Like;
        QStringList actorAccountIds;
    };
    struct ReactionChange {
        QString conversationId;
        qint64 conversationSequence = 0;
        QString messageId;
        ReactionKind reaction = ReactionKind::Like;
        bool active = false;
        QString actorAccountId;
        QString clientOperationId;
        qint64 occurredAtEpochMs = 0;
    };
    struct ReactionCommand {
        QString conversationId;
        QString messageId;
        ReactionKind reaction = ReactionKind::Like;
        bool active = false;
        QString clientOperationId;
        DeliveryState state = DeliveryState::Pending;
    };
    struct PinChange {
        QString conversationId; qint64 conversationSequence = 0; QString messageId;
        bool pinned = false; QString actorAccountId; QString clientOperationId;
        qint64 occurredAtEpochMs = 0;
    };
    struct PinCommand {
        QString conversationId; QString messageId; bool pinned = false;
        QString clientOperationId; DeliveryState state = DeliveryState::Pending;
    };
    struct EditChange {
        QString conversationId; qint64 conversationSequence = 0; QString messageId;
        int contentRevision = 0; QString text; QString actorAccountId;
        QString clientOperationId; qint64 occurredAtEpochMs = 0;
        QList<Mention> mentions;
    };
    struct EditCommand {
        QString conversationId; QString messageId; int expectedRevision = 0;
        QString proposedText; QString clientOperationId;
        EditDeliveryState state = EditDeliveryState::Pending;
        QList<Mention> mentions;
    };
    struct ReplyReference {
        QString targetMessageId;
        qint64 targetConversationSequence = 0;
        QString targetSenderAccountId;
    };
    struct Message {
        QString conversationId;
        QString messageId;
        qint64 conversationSequence = 0;
        QString senderAccountId;
        QString senderDeviceId;
        QString clientMessageId;
        QString text;
        qint64 acceptedAtEpochMs = 0;
        qint64 createdAtEpochMs = 0;
        DeliveryState state = DeliveryState::Pending;
        bool recalled = false;
        bool hasReply = false;
        ReplyReference reply;
        QList<ReactionAggregate> reactions;
        bool pinned = false;
        int contentRevision = 0;
        qint64 editedAtEpochMs = 0;
        QList<Mention> mentions;
        bool forwarded = false;
        QString forwardSourceConversationId;
        QString forwardSourceMessageId;
        int expectedForwardSourceRevision = 0;
    };
    struct Snapshot {
        QList<Message> messages;
        qint64 cursor = 0;
        QString draft;
        QList<ReactionCommand> reactionCommands;
        QList<PinCommand> pinCommands;
        QList<EditCommand> editCommands;
    };

    static constexpr int MaxMessagesPerConversation = 500;
    static constexpr int MaxUnacceptedPerAccount = 256;
    static constexpr int MaxTextBytes = 65536;
    static constexpr int MaxDraftLength = 10000;
    static constexpr int MaxPendingReactionsPerAccount = 256;
    static constexpr int MaxPendingPinsPerAccount = 256;
    static constexpr int MaxPendingEditsPerAccount = 256;
    static constexpr int MaxContentRevisions = 100;

    explicit V2LocalMessageRepository(const QString &databasePath);
    ~V2LocalMessageRepository();
    V2LocalMessageRepository(const V2LocalMessageRepository &) = delete;
    V2LocalMessageRepository &operator=(const V2LocalMessageRepository &) = delete;

    static QString defaultDatabasePath(const QString &accountId);
    bool initialize();
    bool upsertPending(const QString &accountId, const Message &message);
    bool markFailed(const QString &accountId, const QString &conversationId,
                    const QString &clientMessageId);
    bool applyAccepted(const QString &accountId, const QString &conversationId,
                       const QString &clientMessageId, const QString &messageId,
                       qint64 conversationSequence, qint64 acceptedAtEpochMs);
    bool mergeServerMessage(const QString &accountId, const Message &message,
                            qint64 cursor);
    bool mergeServerPage(const QString &accountId, const QString &conversationId,
                         const QList<Message> &messages, qint64 nextCursor,
                         const QStringList &recalledMessageIds = {},
                         const QStringList &deletedMessageIds = {},
                         const QList<ReactionChange> &reactionChanges = {},
                         const QList<PinChange> &pinChanges = {},
                         const QList<EditChange> &editChanges = {});
    bool mergeLiveMessage(const QString &accountId, const Message &message);
    bool stageReaction(const QString &accountId, const ReactionCommand &command);
    bool markReactionFailed(const QString &accountId, const QString &clientOperationId);
    bool applyReaction(const QString &accountId, const ReactionChange &change);
    bool mergeLiveReaction(const QString &accountId, const ReactionChange &change);
    bool stagePin(const QString &accountId, const PinCommand &command);
    bool markPinFailed(const QString &accountId, const QString &clientOperationId);
    bool applyPin(const QString &accountId, const PinChange &change);
    bool mergeLivePin(const QString &accountId, const PinChange &change);
    bool stageEdit(const QString &accountId, const EditCommand &command);
    bool markEditFailed(const QString &accountId, const QString &clientOperationId,
                        bool conflict);
    bool rebaseEdit(const QString &accountId, const QString &staleOperationId,
                    const EditCommand &replacement);
    bool discardEdit(const QString &accountId, const QString &clientOperationId);
    bool applyEdit(const QString &accountId, const EditChange &change);
    bool mergeLiveEdit(const QString &accountId, const EditChange &change);
    bool saveDraft(const QString &accountId, const QString &conversationId,
                   const QString &draft);
    Snapshot loadSnapshot(const QString &accountId, const QString &conversationId);
    QList<Message> pendingSends(const QString &accountId);
    QList<ReactionCommand> pendingReactions(const QString &accountId);
    QList<PinCommand> pendingPins(const QString &accountId);
    QList<EditCommand> pendingEdits(const QString &accountId);
    QString lastError() const { return m_lastError; }

private:
    static bool canonicalUuid(const QString &value);
    static bool validIdentifier(const QString &value);
    static QString stateValue(DeliveryState state);
    static bool parseState(const QString &value, DeliveryState *state);
    static bool validBaseMessage(const Message &message);
    static bool validMentions(const QString &text, const QList<Mention> &mentions);
    static bool sameMentions(const QList<Mention> &left, const QList<Mention> &right);
    static bool validPending(const Message &message);
    static bool validAccepted(const Message &message);
    static bool validReactionKind(ReactionKind reaction);
    static bool validReactionChange(const ReactionChange &change, bool sequenceRequired);
    static bool validReactionCommand(const ReactionCommand &command);
    static bool validPinChange(const PinChange &change, bool sequenceRequired);
    static bool validPinCommand(const PinCommand &command);
    static bool validEditChange(const EditChange &change, bool sequenceRequired);
    static bool validEditCommand(const EditCommand &command);
    bool ensureConversation(const QString &accountId, const QString &conversationId,
                            qint64 cursor);
    bool insertMessage(const QString &accountId, const Message &message);
    bool insertMessageMentions(const QString &accountId, const QString &conversationId,
                               const QString &clientMessageId,
                               const QList<Mention> &mentions);
    bool insertEditMentions(const QString &accountId, const QString &clientOperationId,
                            const QList<Mention> &mentions);
    bool pruneAccepted(const QString &accountId, const QString &conversationId);
    bool applyReactionProjection(const QString &accountId, const ReactionChange &change);
    bool applyPinProjection(const QString &accountId, const PinChange &change);
    bool applyEditProjection(const QString &accountId, const EditChange &change);
    bool fail(const QString &operation, const QString &detail);

    QString m_databasePath;
    QString m_connectionName;
    QSqlDatabase m_database;
    QString m_lastError;
};
