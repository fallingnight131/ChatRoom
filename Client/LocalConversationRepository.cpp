#include "LocalConversationRepository.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QSet>
#include <QSqlError>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QUuid>
#include <QDebug>

namespace {
constexpr int SchemaVersion = 1;
}

LocalConversationRepository::LocalConversationRepository(const QString &databasePath)
    : m_databasePath(databasePath),
      m_connectionName(QStringLiteral("chat-client-local-%1")
                           .arg(QUuid::createUuid().toString(QUuid::WithoutBraces))) {}

LocalConversationRepository::~LocalConversationRepository() {
    if (m_database.isValid()) m_database.close();
    m_database = QSqlDatabase();
    QSqlDatabase::removeDatabase(m_connectionName);
}

QString LocalConversationRepository::defaultDatabasePath(const QString &account) {
    const QByteArray accountHash = QCryptographicHash::hash(
        account.toUtf8(), QCryptographicHash::Sha256).toHex();
    const QString directory = QStandardPaths::writableLocation(
        QStandardPaths::AppLocalDataLocation) + QStringLiteral("/accounts/") +
        QString::fromLatin1(accountHash);
    QDir().mkpath(directory);
    return directory + QStringLiteral("/conversations.sqlite");
}

bool LocalConversationRepository::initialize() {
    const QFileInfo fileInfo(m_databasePath);
    if (!QDir().mkpath(fileInfo.absolutePath()))
        return fail(QStringLiteral("initialize"), QStringLiteral("cannot create database directory"));

    m_database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connectionName);
    m_database.setDatabaseName(m_databasePath);
    if (!m_database.open())
        return fail(QStringLiteral("initialize"), m_database.lastError().text());

    QSqlQuery pragma(m_database);
    if (!pragma.exec(QStringLiteral("PRAGMA foreign_keys = ON")) ||
        !pragma.exec(QStringLiteral("PRAGMA journal_mode = WAL")) ||
        !pragma.exec(QStringLiteral("PRAGMA synchronous = NORMAL"))) {
        return fail(QStringLiteral("initialize"), pragma.lastError().text());
    }
    if (!pragma.exec(QStringLiteral("PRAGMA user_version")) || !pragma.next())
        return fail(QStringLiteral("initialize"), pragma.lastError().text());
    const int version = pragma.value(0).toInt();
    if (version > SchemaVersion) {
        return fail(QStringLiteral("initialize"),
                    QStringLiteral("database schema %1 is newer than supported %2")
                        .arg(version).arg(SchemaVersion));
    }

    if (!m_database.transaction())
        return fail(QStringLiteral("migrate"), m_database.lastError().text());
    QSqlQuery query(m_database);
    const QStringList statements = {
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS conversations ("
            "account TEXT NOT NULL, kind TEXT NOT NULL, conversation_key TEXT NOT NULL, "
            "cursor INTEGER NOT NULL DEFAULT 0 CHECK(cursor >= 0), "
            "draft TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, "
            "PRIMARY KEY(account, kind, conversation_key), "
            "CHECK(kind IN ('room', 'direct')))"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS messages ("
            "account TEXT NOT NULL, kind TEXT NOT NULL, conversation_key TEXT NOT NULL, "
            "identity TEXT NOT NULL, server_id INTEGER NOT NULL DEFAULT 0, "
            "client_message_id TEXT NOT NULL DEFAULT '', sequence INTEGER NOT NULL DEFAULT 0, "
            "timestamp INTEGER NOT NULL, payload_json TEXT NOT NULL, updated_at INTEGER NOT NULL, "
            "PRIMARY KEY(account, kind, conversation_key, identity), "
            "FOREIGN KEY(account, kind, conversation_key) REFERENCES conversations"
            "(account, kind, conversation_key) ON DELETE CASCADE)"),
        QStringLiteral(
            "CREATE INDEX IF NOT EXISTS idx_local_messages_order "
            "ON messages(account, kind, conversation_key, timestamp, sequence)"),
        QStringLiteral("PRAGMA user_version = 1")
    };
    for (const QString &statement : statements) {
        if (!query.exec(statement)) {
            m_database.rollback();
            return fail(QStringLiteral("migrate"), query.lastError().text());
        }
    }
    if (!m_database.commit())
        return fail(QStringLiteral("migrate"), m_database.lastError().text());

    qInfo().noquote() << QStringLiteral("[LocalStore] operation=initialize outcome=success schema=%1")
                             .arg(SchemaVersion);
    m_lastError.clear();
    return true;
}

bool LocalConversationRepository::replaceMessages(
    const QString &account, Kind kind, const QString &conversationKey,
    const QList<Message> &messages, qint64 cursor) {
    if (!validateIdentity(account, conversationKey) || cursor < 0)
        return fail(QStringLiteral("replaceMessages"), QStringLiteral("invalid identity or cursor"));
    if (!m_database.transaction())
        return fail(QStringLiteral("replaceMessages"), m_database.lastError().text());
    if (!ensureConversation(account, kind, conversationKey, cursor)) {
        m_database.rollback();
        return false;
    }

    QSqlQuery remove(m_database);
    remove.prepare(QStringLiteral(
        "DELETE FROM messages WHERE account = ? AND kind = ? AND conversation_key = ?"));
    remove.addBindValue(account);
    remove.addBindValue(kindValue(kind));
    remove.addBindValue(conversationKey);
    if (!remove.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("replaceMessages"), remove.lastError().text());
    }

    QSqlQuery insert(m_database);
    insert.prepare(QStringLiteral(
        "INSERT INTO messages(account, kind, conversation_key, identity, server_id, "
        "client_message_id, sequence, timestamp, payload_json, updated_at) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
    const int first = qMax(0, messages.size() - MaxMessagesPerConversation);
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    for (int index = first; index < messages.size(); ++index) {
        const Message &message = messages[index];
        insert.bindValue(0, account);
        insert.bindValue(1, kindValue(kind));
        insert.bindValue(2, conversationKey);
        insert.bindValue(3, messageIdentity(message, index));
        insert.bindValue(4, message.id());
        insert.bindValue(5, message.clientMessageId());
        insert.bindValue(6, message.sequence());
        insert.bindValue(7, message.timestamp().toMSecsSinceEpoch());
        insert.bindValue(8, QString::fromUtf8(serializeMessage(message)));
        insert.bindValue(9, now);
        if (!insert.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("replaceMessages"), insert.lastError().text());
        }
    }
    if (!m_database.commit())
        return fail(QStringLiteral("replaceMessages"), m_database.lastError().text());
    m_lastError.clear();
    return true;
}

bool LocalConversationRepository::upsertMessage(
    const QString &account, Kind kind, const QString &conversationKey,
    const Message &message, qint64 cursor) {
    if (!validateIdentity(account, conversationKey) || cursor < 0) return false;
    if (!m_database.transaction())
        return fail(QStringLiteral("upsertMessage"), m_database.lastError().text());
    if (!ensureConversation(account, kind, conversationKey, cursor)) {
        m_database.rollback();
        return false;
    }

    QSqlQuery removeExisting(m_database);
    removeExisting.prepare(QStringLiteral(
        "DELETE FROM messages WHERE account = ? AND kind = ? AND conversation_key = ? "
        "AND ((? > 0 AND server_id = ?) OR (? <> '' AND client_message_id = ?))"));
    removeExisting.addBindValue(account);
    removeExisting.addBindValue(kindValue(kind));
    removeExisting.addBindValue(conversationKey);
    removeExisting.addBindValue(message.id());
    removeExisting.addBindValue(message.id());
    removeExisting.addBindValue(message.clientMessageId());
    removeExisting.addBindValue(message.clientMessageId());
    if (!removeExisting.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertMessage"), removeExisting.lastError().text());
    }

    QSqlQuery insert(m_database);
    insert.prepare(QStringLiteral(
        "INSERT INTO messages(account, kind, conversation_key, identity, server_id, "
        "client_message_id, sequence, timestamp, payload_json, updated_at) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
    insert.addBindValue(account);
    insert.addBindValue(kindValue(kind));
    insert.addBindValue(conversationKey);
    insert.addBindValue(messageIdentity(message, 0));
    insert.addBindValue(message.id());
    insert.addBindValue(message.clientMessageId());
    insert.addBindValue(message.sequence());
    insert.addBindValue(message.timestamp().toMSecsSinceEpoch());
    insert.addBindValue(QString::fromUtf8(serializeMessage(message)));
    insert.addBindValue(QDateTime::currentMSecsSinceEpoch());
    if (!insert.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertMessage"), insert.lastError().text());
    }

    QSqlQuery prune(m_database);
    prune.prepare(QStringLiteral(
        "DELETE FROM messages WHERE rowid IN ("
        "SELECT rowid FROM messages WHERE account = ? AND kind = ? AND conversation_key = ? "
        "ORDER BY timestamp DESC, sequence DESC, rowid DESC LIMIT -1 OFFSET ?)"));
    prune.addBindValue(account);
    prune.addBindValue(kindValue(kind));
    prune.addBindValue(conversationKey);
    prune.addBindValue(MaxMessagesPerConversation);
    if (!prune.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertMessage"), prune.lastError().text());
    }
    if (!m_database.commit())
        return fail(QStringLiteral("upsertMessage"), m_database.lastError().text());
    m_lastError.clear();
    return true;
}

LocalConversationRepository::Snapshot LocalConversationRepository::loadSnapshot(
    const QString &account, Kind kind, const QString &conversationKey) {
    Snapshot snapshot;
    if (!validateIdentity(account, conversationKey)) return snapshot;

    QSqlQuery conversation(m_database);
    conversation.prepare(QStringLiteral(
        "SELECT cursor, draft FROM conversations "
        "WHERE account = ? AND kind = ? AND conversation_key = ?"));
    conversation.addBindValue(account);
    conversation.addBindValue(kindValue(kind));
    conversation.addBindValue(conversationKey);
    if (!conversation.exec()) {
        fail(QStringLiteral("loadSnapshot"), conversation.lastError().text());
        return snapshot;
    }
    if (!conversation.next()) {
        m_lastError.clear();
        return snapshot;
    }
    snapshot.cursor = conversation.value(0).toLongLong();
    snapshot.draft = conversation.value(1).toString();

    QSqlQuery messages(m_database);
    messages.prepare(QStringLiteral(
        "SELECT payload_json FROM messages "
        "WHERE account = ? AND kind = ? AND conversation_key = ? "
        "ORDER BY timestamp ASC, sequence ASC"));
    messages.addBindValue(account);
    messages.addBindValue(kindValue(kind));
    messages.addBindValue(conversationKey);
    if (!messages.exec()) {
        fail(QStringLiteral("loadSnapshot"), messages.lastError().text());
        return {};
    }
    while (messages.next()) {
        Message message;
        if (deserializeMessage(messages.value(0).toByteArray(), &message))
            snapshot.messages.append(message);
    }
    m_lastError.clear();
    return snapshot;
}

bool LocalConversationRepository::saveDraft(const QString &account, Kind kind,
                                             const QString &conversationKey,
                                             const QString &draft) {
    if (!validateIdentity(account, conversationKey)) return false;
    if (!ensureConversation(account, kind, conversationKey, 0)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE conversations SET draft = ?, updated_at = ? "
        "WHERE account = ? AND kind = ? AND conversation_key = ?"));
    query.addBindValue(draft.left(MaxDraftLength));
    query.addBindValue(QDateTime::currentMSecsSinceEpoch());
    query.addBindValue(account);
    query.addBindValue(kindValue(kind));
    query.addBindValue(conversationKey);
    if (!query.exec()) return fail(QStringLiteral("saveDraft"), query.lastError().text());
    m_lastError.clear();
    return true;
}

bool LocalConversationRepository::removeConversation(
    const QString &account, Kind kind, const QString &conversationKey) {
    if (!validateIdentity(account, conversationKey)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "DELETE FROM conversations WHERE account = ? AND kind = ? AND conversation_key = ?"));
    query.addBindValue(account);
    query.addBindValue(kindValue(kind));
    query.addBindValue(conversationKey);
    if (!query.exec())
        return fail(QStringLiteral("removeConversation"), query.lastError().text());
    m_lastError.clear();
    return true;
}

bool LocalConversationRepository::pruneConversations(
    const QString &account, Kind kind, const QSet<QString> &allowedConversationKeys) {
    if (account.isEmpty()) return false;
    QSqlQuery list(m_database);
    list.prepare(QStringLiteral(
        "SELECT conversation_key FROM conversations WHERE account = ? AND kind = ?"));
    list.addBindValue(account);
    list.addBindValue(kindValue(kind));
    if (!list.exec()) return fail(QStringLiteral("pruneConversations"), list.lastError().text());
    QStringList removals;
    while (list.next()) {
        const QString key = list.value(0).toString();
        if (!allowedConversationKeys.contains(key)) removals.append(key);
    }
    list.finish();
    if (!m_database.transaction())
        return fail(QStringLiteral("pruneConversations"), m_database.lastError().text());
    for (const QString &key : removals) {
        if (!removeConversation(account, kind, key)) {
            m_database.rollback();
            return false;
        }
    }
    if (!m_database.commit())
        return fail(QStringLiteral("pruneConversations"), m_database.lastError().text());
    return true;
}

bool LocalConversationRepository::copyAccountTo(
    LocalConversationRepository &target, const QString &sourceAccount,
    const QString &targetAccount) {
    if (sourceAccount.isEmpty() || targetAccount.isEmpty()
        || !m_database.isOpen() || !target.m_database.isOpen()) {
        return fail(QStringLiteral("copyAccountTo"),
                    QStringLiteral("source/target repository or account is invalid"));
    }

    struct ConversationRef { Kind kind; QString key; };
    QList<ConversationRef> conversations;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT kind, conversation_key FROM conversations WHERE account = ?"));
    query.addBindValue(sourceAccount);
    if (!query.exec()) return fail(QStringLiteral("copyAccountTo"), query.lastError().text());
    while (query.next()) {
        const QString storedKind = query.value(0).toString();
        if (storedKind != QStringLiteral("room") && storedKind != QStringLiteral("direct"))
            return fail(QStringLiteral("copyAccountTo"),
                        QStringLiteral("unknown conversation kind: %1").arg(storedKind));
        conversations.append({storedKind == QStringLiteral("room") ? Kind::Room : Kind::Direct,
                              query.value(1).toString()});
    }
    query.finish();

    for (const ConversationRef &conversation : conversations) {
        const Snapshot snapshot = loadSnapshot(sourceAccount, conversation.kind,
                                               conversation.key);
        if (!m_lastError.isEmpty()) return false;
        QList<Message> migratedMessages = snapshot.messages;
        for (Message &message : migratedMessages) {
            if (message.sender() == sourceAccount)
                message.setSender(targetAccount);
        }
        if (!target.replaceMessages(targetAccount, conversation.kind,
                                    conversation.key, migratedMessages,
                                    snapshot.cursor)) {
            return fail(QStringLiteral("copyAccountTo"), target.lastError());
        }
        if (!target.saveDraft(targetAccount, conversation.kind,
                              conversation.key, snapshot.draft)) {
            return fail(QStringLiteral("copyAccountTo"), target.lastError());
        }
    }
    m_lastError.clear();
    return true;
}

QList<LocalConversationRepository::PendingSend>
LocalConversationRepository::pendingSends(const QString &account, Kind kind) {
    QList<PendingSend> pending;
    if (account.isEmpty() || !m_database.isOpen()) return pending;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT conversation_key, payload_json FROM messages "
        "WHERE account = ? AND kind = ? AND server_id <= 0 "
        "AND client_message_id <> '' ORDER BY timestamp ASC"));
    query.addBindValue(account);
    query.addBindValue(kindValue(kind));
    if (!query.exec()) {
        fail(QStringLiteral("pendingSends"), query.lastError().text());
        return pending;
    }
    while (query.next()) {
        Message message;
        if (!deserializeMessage(query.value(1).toByteArray(), &message)) continue;
        if (message.deliveryState() != Message::Sending) continue;
        pending.append({kind, query.value(0).toString(), message});
    }
    m_lastError.clear();
    return pending;
}

bool LocalConversationRepository::clearCachedMessages(const QString &account) {
    if (account.isEmpty() || !m_database.isOpen())
        return fail(QStringLiteral("clearCachedMessages"),
                    QStringLiteral("invalid account or closed database"));
    if (!m_database.transaction())
        return fail(QStringLiteral("clearCachedMessages"),
                    m_database.lastError().text());

    QSqlQuery remove(m_database);
    remove.prepare(QStringLiteral(
        "DELETE FROM messages WHERE account = ? AND NOT "
        "(server_id <= 0 AND client_message_id <> '')"));
    remove.addBindValue(account);
    if (!remove.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("clearCachedMessages"),
                    remove.lastError().text());
    }

    QSqlQuery resetCursors(m_database);
    resetCursors.prepare(QStringLiteral(
        "UPDATE conversations SET cursor = 0, updated_at = ? WHERE account = ?"));
    resetCursors.addBindValue(QDateTime::currentMSecsSinceEpoch());
    resetCursors.addBindValue(account);
    if (!resetCursors.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("clearCachedMessages"),
                    resetCursors.lastError().text());
    }
    if (!m_database.commit())
        return fail(QStringLiteral("clearCachedMessages"),
                    m_database.lastError().text());
    m_lastError.clear();
    return true;
}

QString LocalConversationRepository::kindValue(Kind kind) {
    return kind == Kind::Room ? QStringLiteral("room") : QStringLiteral("direct");
}

QString LocalConversationRepository::messageIdentity(const Message &message, int position) {
    if (message.id() > 0) return QStringLiteral("server:%1").arg(message.id());
    if (!message.clientMessageId().isEmpty())
        return QStringLiteral("client:%1").arg(message.clientMessageId());
    if (message.sequence() > 0) return QStringLiteral("sequence:%1").arg(message.sequence());
    return QStringLiteral("local:%1:%2")
        .arg(message.timestamp().toMSecsSinceEpoch()).arg(position);
}

QByteArray LocalConversationRepository::serializeMessage(const Message &message) {
    QJsonObject envelope = message.toJson();
    QJsonObject data = envelope["data"].toObject();
    data.remove(QStringLiteral("imageData"));
    data.remove(QStringLiteral("thumbnail"));
    envelope["data"] = data;
    return QJsonDocument(envelope).toJson(QJsonDocument::Compact);
}

bool LocalConversationRepository::deserializeMessage(const QByteArray &payload,
                                                      Message *message) {
    QJsonParseError error;
    const QJsonDocument document = QJsonDocument::fromJson(payload, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) return false;
    *message = Message::fromJson(document.object());
    return true;
}

bool LocalConversationRepository::ensureConversation(
    const QString &account, Kind kind, const QString &conversationKey, qint64 cursor) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO conversations(account, kind, conversation_key, cursor, updated_at) "
        "VALUES(?, ?, ?, ?, ?) "
        "ON CONFLICT(account, kind, conversation_key) DO UPDATE SET "
        "cursor = MAX(conversations.cursor, excluded.cursor), updated_at = excluded.updated_at"));
    query.addBindValue(account);
    query.addBindValue(kindValue(kind));
    query.addBindValue(conversationKey);
    query.addBindValue(cursor);
    query.addBindValue(QDateTime::currentMSecsSinceEpoch());
    if (!query.exec()) return fail(QStringLiteral("ensureConversation"), query.lastError().text());
    return true;
}

bool LocalConversationRepository::validateIdentity(
    const QString &account, const QString &conversationKey) {
    return !account.isEmpty() && !conversationKey.isEmpty() && m_database.isOpen();
}

bool LocalConversationRepository::fail(const QString &operation, const QString &detail) {
    m_lastError = detail;
    qWarning().noquote() << QStringLiteral("[LocalStore] operation=%1 outcome=failed detail=%2")
                                .arg(operation, detail);
    return false;
}
