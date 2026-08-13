#pragma once

#include <QList>
#include <QSqlDatabase>
#include <QString>

class V2LocalMessageRepository final {
public:
    enum class DeliveryState { Pending, Failed, Accepted };
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
        bool hasReply = false;
        ReplyReference reply;
    };
    struct Snapshot {
        QList<Message> messages;
        qint64 cursor = 0;
        QString draft;
    };

    static constexpr int MaxMessagesPerConversation = 500;
    static constexpr int MaxUnacceptedPerAccount = 256;
    static constexpr int MaxTextBytes = 65536;
    static constexpr int MaxDraftLength = 10000;

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
    bool saveDraft(const QString &accountId, const QString &conversationId,
                   const QString &draft);
    Snapshot loadSnapshot(const QString &accountId, const QString &conversationId);
    QList<Message> pendingSends(const QString &accountId);
    QString lastError() const { return m_lastError; }

private:
    static bool canonicalUuid(const QString &value);
    static bool validIdentifier(const QString &value);
    static QString stateValue(DeliveryState state);
    static bool parseState(const QString &value, DeliveryState *state);
    static bool validBaseMessage(const Message &message);
    static bool validPending(const Message &message);
    static bool validAccepted(const Message &message);
    bool ensureConversation(const QString &accountId, const QString &conversationId,
                            qint64 cursor);
    bool insertMessage(const QString &accountId, const Message &message);
    bool pruneAccepted(const QString &accountId, const QString &conversationId);
    bool fail(const QString &operation, const QString &detail);

    QString m_databasePath;
    QString m_connectionName;
    QSqlDatabase m_database;
    QString m_lastError;
};
