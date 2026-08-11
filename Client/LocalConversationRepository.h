#pragma once

#include <QList>
#include <QSet>
#include <QSqlDatabase>
#include <QString>

#include "Message.h"

class LocalConversationRepository {
public:
    enum class Kind { Room, Direct };
    enum class AttachmentState {
        PendingAuthorization,
        Uploading,
        Finalizing,
        Failed
    };

    struct Snapshot {
        QList<Message> messages;
        qint64 cursor = 0;
        QString draft;
    };
    struct PendingSend {
        Kind kind;
        QString conversationKey;
        Message message;
    };
    struct AttachmentCommand {
        Kind kind = Kind::Room;
        QString conversationKey;
        QString clientMessageId;
        QString sourcePath;
        QString fileName;
        QString contentType;
        qint64 fileSize = 0;
        qint64 sourceModifiedAtMs = 0;
        QString sourceFingerprint;
        AttachmentState state = AttachmentState::PendingAuthorization;
        qint64 transmittedBytes = 0;
        QString failureCode;
    };

    static constexpr int MaxMessagesPerConversation = 500;
    static constexpr int MaxDraftLength = 10000;

    explicit LocalConversationRepository(const QString &databasePath);
    ~LocalConversationRepository();

    LocalConversationRepository(const LocalConversationRepository &) = delete;
    LocalConversationRepository &operator=(const LocalConversationRepository &) = delete;

    static QString defaultDatabasePath(const QString &account);

    bool initialize();
    bool replaceMessages(const QString &account, Kind kind,
                         const QString &conversationKey,
                         const QList<Message> &messages, qint64 cursor);
    bool upsertMessage(const QString &account, Kind kind,
                       const QString &conversationKey,
                       const Message &message, qint64 cursor);
    Snapshot loadSnapshot(const QString &account, Kind kind,
                          const QString &conversationKey);
    bool saveDraft(const QString &account, Kind kind,
                   const QString &conversationKey, const QString &draft);
    bool removeConversation(const QString &account, Kind kind,
                            const QString &conversationKey);
    bool pruneConversations(const QString &account, Kind kind,
                            const QSet<QString> &allowedConversationKeys);
    bool copyAccountTo(LocalConversationRepository &target,
                       const QString &sourceAccount,
                       const QString &targetAccount);
    QList<PendingSend> pendingSends(const QString &account, Kind kind);
    bool upsertAttachmentCommand(const QString &account,
                                 const AttachmentCommand &command);
    QList<AttachmentCommand> attachmentCommands(const QString &account,
                                                Kind kind);
    bool updateAttachmentCommandState(const QString &account,
                                      const QString &clientMessageId,
                                      AttachmentState state,
                                      qint64 transmittedBytes = 0,
                                      const QString &failureCode = QString());
    bool removeAttachmentCommand(const QString &account,
                                 const QString &clientMessageId);
    bool clearCachedMessages(const QString &account);

    QString lastError() const { return m_lastError; }

private:
    static QString kindValue(Kind kind);
    static QString attachmentStateValue(AttachmentState state);
    static bool parseAttachmentState(const QString &value,
                                     AttachmentState *state);
    static QString messageIdentity(const Message &message, int position);
    static QByteArray serializeMessage(const Message &message);
    static bool deserializeMessage(const QByteArray &payload, Message *message);

    bool ensureConversation(const QString &account, Kind kind,
                            const QString &conversationKey, qint64 cursor);
    bool validateIdentity(const QString &account,
                          const QString &conversationKey);
    bool fail(const QString &operation, const QString &detail);

    QString m_databasePath;
    QString m_connectionName;
    QSqlDatabase m_database;
    QString m_lastError;
};
