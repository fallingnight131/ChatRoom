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
constexpr int SchemaVersion = 2;
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
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS attachment_outbox ("
            "account TEXT NOT NULL, client_message_id TEXT NOT NULL, "
            "kind TEXT NOT NULL, conversation_key TEXT NOT NULL, "
            "source_path TEXT NOT NULL, file_name TEXT NOT NULL, "
            "content_type TEXT NOT NULL, file_size INTEGER NOT NULL CHECK(file_size > 0), "
            "source_modified_at INTEGER NOT NULL, source_fingerprint TEXT NOT NULL, "
            "state TEXT NOT NULL, transmitted_bytes INTEGER NOT NULL DEFAULT 0 "
            "CHECK(transmitted_bytes >= 0 AND transmitted_bytes <= file_size), "
            "failure_code TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, "
            "updated_at INTEGER NOT NULL, PRIMARY KEY(account, client_message_id), "
            "FOREIGN KEY(account, kind, conversation_key) REFERENCES conversations"
            "(account, kind, conversation_key) ON DELETE CASCADE, "
            "CHECK(kind IN ('room', 'direct')), "
            "CHECK(state IN ('pending_authorization', 'uploading', 'finalizing', 'failed')))"),
        QStringLiteral(
            "CREATE INDEX IF NOT EXISTS idx_attachment_outbox_conversation "
            "ON attachment_outbox(account, kind, conversation_key, created_at)"),
        QStringLiteral("PRAGMA user_version = 2")
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
        insert.bindValue(5, message.clientMessageId().isNull()
                                ? QStringLiteral("")
                                : message.clientMessageId());
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
    const QString clientMessageId = message.clientMessageId().isNull()
        ? QStringLiteral("") : message.clientMessageId();
    removeExisting.addBindValue(clientMessageId);
    removeExisting.addBindValue(clientMessageId);
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
    insert.addBindValue(clientMessageId);
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

    for (Kind kind : {Kind::Room, Kind::Direct}) {
        const QList<AttachmentCommand> commands = attachmentCommands(sourceAccount, kind);
        if (!m_lastError.isEmpty()) return false;
        for (const AttachmentCommand &command : commands) {
            if (!target.upsertAttachmentCommand(targetAccount, command))
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

bool LocalConversationRepository::upsertAttachmentCommand(
    const QString &account, const AttachmentCommand &command) {
    const bool valid = validateIdentity(account, command.conversationKey)
        && !command.clientMessageId.isEmpty()
        && command.clientMessageId.toUtf8().size() <= 128
        && !command.sourcePath.isEmpty() && command.sourcePath.size() <= 4096
        && !command.fileName.isEmpty() && command.fileName.size() <= 255
        && !command.contentType.isEmpty() && command.contentType.size() <= 32
        && command.fileSize > 0 && command.sourceModifiedAtMs >= 0
        && !command.sourceFingerprint.isEmpty()
        && command.sourceFingerprint.size() <= 128
        && command.transmittedBytes >= 0
        && command.transmittedBytes <= command.fileSize
        && command.failureCode.size() <= 128;
    if (!valid)
        return fail(QStringLiteral("upsertAttachmentCommand"),
                    QStringLiteral("invalid attachment command"));
    if (!m_database.transaction())
        return fail(QStringLiteral("upsertAttachmentCommand"),
                    m_database.lastError().text());
    if (!ensureConversation(account, command.kind, command.conversationKey, 0)) {
        m_database.rollback();
        return false;
    }

    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO attachment_outbox(account, client_message_id, kind, conversation_key, "
        "source_path, file_name, content_type, file_size, source_modified_at, "
        "source_fingerprint, state, transmitted_bytes, failure_code, created_at, updated_at) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        "ON CONFLICT(account, client_message_id) DO UPDATE SET "
        "kind = excluded.kind, conversation_key = excluded.conversation_key, "
        "source_path = excluded.source_path, file_name = excluded.file_name, "
        "content_type = excluded.content_type, file_size = excluded.file_size, "
        "source_modified_at = excluded.source_modified_at, "
        "source_fingerprint = excluded.source_fingerprint, state = excluded.state, "
        "transmitted_bytes = excluded.transmitted_bytes, "
        "failure_code = excluded.failure_code, updated_at = excluded.updated_at"));
    query.addBindValue(account);
    query.addBindValue(command.clientMessageId);
    query.addBindValue(kindValue(command.kind));
    query.addBindValue(command.conversationKey);
    query.addBindValue(command.sourcePath);
    query.addBindValue(command.fileName);
    query.addBindValue(command.contentType);
    query.addBindValue(command.fileSize);
    query.addBindValue(command.sourceModifiedAtMs);
    query.addBindValue(command.sourceFingerprint);
    query.addBindValue(attachmentStateValue(command.state));
    query.addBindValue(command.transmittedBytes);
    query.addBindValue(command.failureCode.isNull()
                           ? QStringLiteral("") : command.failureCode);
    query.addBindValue(now);
    query.addBindValue(now);
    if (!query.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertAttachmentCommand"), query.lastError().text());
    }
    if (!m_database.commit())
        return fail(QStringLiteral("upsertAttachmentCommand"),
                    m_database.lastError().text());
    m_lastError.clear();
    return true;
}

QList<LocalConversationRepository::AttachmentCommand>
LocalConversationRepository::attachmentCommands(const QString &account, Kind kind) {
    QList<AttachmentCommand> commands;
    if (account.isEmpty() || !m_database.isOpen()) {
        fail(QStringLiteral("attachmentCommands"),
             QStringLiteral("invalid account or closed database"));
        return commands;
    }
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT conversation_key, client_message_id, source_path, file_name, "
        "content_type, file_size, source_modified_at, source_fingerprint, state, "
        "transmitted_bytes, failure_code FROM attachment_outbox "
        "WHERE account = ? AND kind = ? ORDER BY created_at ASC"));
    query.addBindValue(account);
    query.addBindValue(kindValue(kind));
    if (!query.exec()) {
        fail(QStringLiteral("attachmentCommands"), query.lastError().text());
        return commands;
    }
    while (query.next()) {
        AttachmentCommand command;
        command.kind = kind;
        command.conversationKey = query.value(0).toString();
        command.clientMessageId = query.value(1).toString();
        command.sourcePath = query.value(2).toString();
        command.fileName = query.value(3).toString();
        command.contentType = query.value(4).toString();
        command.fileSize = query.value(5).toLongLong();
        command.sourceModifiedAtMs = query.value(6).toLongLong();
        command.sourceFingerprint = query.value(7).toString();
        if (!parseAttachmentState(query.value(8).toString(), &command.state)) {
            fail(QStringLiteral("attachmentCommands"),
                 QStringLiteral("unknown attachment state"));
            return {};
        }
        command.transmittedBytes = query.value(9).toLongLong();
        command.failureCode = query.value(10).toString();
        commands.append(command);
    }
    m_lastError.clear();
    return commands;
}

bool LocalConversationRepository::updateAttachmentCommandState(
    const QString &account, const QString &clientMessageId,
    AttachmentState state, qint64 transmittedBytes, const QString &failureCode) {
    if (account.isEmpty() || clientMessageId.isEmpty() || transmittedBytes < 0
        || failureCode.size() > 128 || !m_database.isOpen()) {
        return fail(QStringLiteral("updateAttachmentCommandState"),
                    QStringLiteral("invalid attachment state update"));
    }
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE attachment_outbox SET state = ?, transmitted_bytes = MIN(?, file_size), "
        "failure_code = ?, updated_at = ? WHERE account = ? AND client_message_id = ?"));
    query.addBindValue(attachmentStateValue(state));
    query.addBindValue(transmittedBytes);
    query.addBindValue(failureCode.isNull() ? QStringLiteral("") : failureCode);
    query.addBindValue(QDateTime::currentMSecsSinceEpoch());
    query.addBindValue(account);
    query.addBindValue(clientMessageId);
    if (!query.exec())
        return fail(QStringLiteral("updateAttachmentCommandState"), query.lastError().text());
    if (query.numRowsAffected() != 1)
        return fail(QStringLiteral("updateAttachmentCommandState"),
                    QStringLiteral("attachment command not found"));
    m_lastError.clear();
    return true;
}

bool LocalConversationRepository::removeAttachmentCommand(
    const QString &account, const QString &clientMessageId) {
    if (account.isEmpty() || clientMessageId.isEmpty() || !m_database.isOpen())
        return fail(QStringLiteral("removeAttachmentCommand"),
                    QStringLiteral("invalid attachment command identity"));
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "DELETE FROM attachment_outbox WHERE account = ? AND client_message_id = ?"));
    query.addBindValue(account);
    query.addBindValue(clientMessageId);
    if (!query.exec())
        return fail(QStringLiteral("removeAttachmentCommand"), query.lastError().text());
    m_lastError.clear();
    return true;
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

QString LocalConversationRepository::attachmentStateValue(AttachmentState state) {
    switch (state) {
    case AttachmentState::PendingAuthorization:
        return QStringLiteral("pending_authorization");
    case AttachmentState::Uploading:
        return QStringLiteral("uploading");
    case AttachmentState::Finalizing:
        return QStringLiteral("finalizing");
    case AttachmentState::Failed:
        return QStringLiteral("failed");
    }
    return QStringLiteral("failed");
}

bool LocalConversationRepository::parseAttachmentState(
    const QString &value, AttachmentState *state) {
    if (!state) return false;
    if (value == QLatin1String("pending_authorization"))
        *state = AttachmentState::PendingAuthorization;
    else if (value == QLatin1String("uploading"))
        *state = AttachmentState::Uploading;
    else if (value == QLatin1String("finalizing"))
        *state = AttachmentState::Finalizing;
    else if (value == QLatin1String("failed"))
        *state = AttachmentState::Failed;
    else
        return false;
    return true;
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
