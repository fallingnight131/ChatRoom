#pragma once

#include <QList>
#include <QSet>
#include <QSqlDatabase>
#include <QString>

#include "Message.h"

class LocalConversationRepository {
public:
    enum class Kind { Room, Direct };

    struct Snapshot {
        QList<Message> messages;
        qint64 cursor = 0;
        QString draft;
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

    QString lastError() const { return m_lastError; }

private:
    static QString kindValue(Kind kind);
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
