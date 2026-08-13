#pragma once

#include <QList>
#include <QSqlDatabase>
#include <QString>
#include <QStringList>

class V2LocalMessageRepository final {
public:
    enum class DeliveryState { Pending, Failed, Accepted };
    enum class ReactionKind { Like = 1, Love, Laugh, Surprised, Sad, Angry };
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
    };
    struct Snapshot {
        QList<Message> messages;
        qint64 cursor = 0;
        QString draft;
        QList<ReactionCommand> reactionCommands;
    };

    static constexpr int MaxMessagesPerConversation = 500;
    static constexpr int MaxUnacceptedPerAccount = 256;
    static constexpr int MaxTextBytes = 65536;
    static constexpr int MaxDraftLength = 10000;
    static constexpr int MaxPendingReactionsPerAccount = 256;

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
                         const QList<ReactionChange> &reactionChanges = {});
    bool mergeLiveMessage(const QString &accountId, const Message &message);
    bool stageReaction(const QString &accountId, const ReactionCommand &command);
    bool markReactionFailed(const QString &accountId, const QString &clientOperationId);
    bool applyReaction(const QString &accountId, const ReactionChange &change);
    bool mergeLiveReaction(const QString &accountId, const ReactionChange &change);
    bool saveDraft(const QString &accountId, const QString &conversationId,
                   const QString &draft);
    Snapshot loadSnapshot(const QString &accountId, const QString &conversationId);
    QList<Message> pendingSends(const QString &accountId);
    QList<ReactionCommand> pendingReactions(const QString &accountId);
    QString lastError() const { return m_lastError; }

private:
    static bool canonicalUuid(const QString &value);
    static bool validIdentifier(const QString &value);
    static QString stateValue(DeliveryState state);
    static bool parseState(const QString &value, DeliveryState *state);
    static bool validBaseMessage(const Message &message);
    static bool validPending(const Message &message);
    static bool validAccepted(const Message &message);
    static bool validReactionKind(ReactionKind reaction);
    static bool validReactionChange(const ReactionChange &change, bool sequenceRequired);
    static bool validReactionCommand(const ReactionCommand &command);
    bool ensureConversation(const QString &accountId, const QString &conversationId,
                            qint64 cursor);
    bool insertMessage(const QString &accountId, const Message &message);
    bool pruneAccepted(const QString &accountId, const QString &conversationId);
    bool applyReactionProjection(const QString &accountId, const ReactionChange &change);
    bool fail(const QString &operation, const QString &detail);

    QString m_databasePath;
    QString m_connectionName;
    QSqlDatabase m_database;
    QString m_lastError;
};
