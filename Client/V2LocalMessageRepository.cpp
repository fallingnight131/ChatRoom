#include "V2LocalMessageRepository.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QSqlError>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QStringEncoder>
#include <QUuid>
#include <QDebug>

namespace { constexpr int SchemaVersion = 3; }

V2LocalMessageRepository::V2LocalMessageRepository(const QString &databasePath)
    : m_databasePath(databasePath),
      m_connectionName(QStringLiteral("chat-client-v2-local-%1")
          .arg(QUuid::createUuid().toString(QUuid::WithoutBraces))) {}

V2LocalMessageRepository::~V2LocalMessageRepository() {
    if (m_database.isValid()) m_database.close();
    m_database = QSqlDatabase();
    QSqlDatabase::removeDatabase(m_connectionName);
}

QString V2LocalMessageRepository::defaultDatabasePath(const QString &accountId) {
    const auto hash = QCryptographicHash::hash(accountId.toUtf8(), QCryptographicHash::Sha256).toHex();
    const QString directory = QStandardPaths::writableLocation(
        QStandardPaths::AppLocalDataLocation) + QStringLiteral("/accounts/")
        + QString::fromLatin1(hash);
    QDir().mkpath(directory);
    return directory + QStringLiteral("/v2-messages.sqlite");
}

bool V2LocalMessageRepository::initialize() {
    if (!QDir().mkpath(QFileInfo(m_databasePath).absolutePath()))
        return fail(QStringLiteral("initialize"), QStringLiteral("cannot create directory"));
    m_database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), m_connectionName);
    m_database.setDatabaseName(m_databasePath);
    if (!m_database.open()) return fail(QStringLiteral("initialize"), m_database.lastError().text());
    QSqlQuery query(m_database);
    if (!query.exec(QStringLiteral("PRAGMA foreign_keys = ON"))
            || !query.exec(QStringLiteral("PRAGMA journal_mode = WAL"))
            || !query.exec(QStringLiteral("PRAGMA synchronous = NORMAL"))
            || !query.exec(QStringLiteral("PRAGMA user_version")) || !query.next())
        return fail(QStringLiteral("initialize"), query.lastError().text());
    const int version = query.value(0).toInt();
    if (version > SchemaVersion)
        return fail(QStringLiteral("initialize"), QStringLiteral("newer V2 local schema"));
    if (!m_database.transaction()) return fail(QStringLiteral("migrate"), m_database.lastError().text());
    const QStringList statements = {
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_conversations ("
            "account_id TEXT NOT NULL, conversation_id TEXT NOT NULL, "
            "cursor INTEGER NOT NULL DEFAULT 0 CHECK(cursor >= 0), "
            "draft TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, "
            "PRIMARY KEY(account_id, conversation_id))"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_messages ("
            "account_id TEXT NOT NULL, conversation_id TEXT NOT NULL, "
            "client_message_id TEXT NOT NULL, message_id TEXT NOT NULL DEFAULT '', "
            "conversation_sequence INTEGER NOT NULL DEFAULT 0 CHECK(conversation_sequence >= 0), "
            "sender_account_id TEXT NOT NULL, sender_device_id TEXT NOT NULL, "
            "text_content TEXT NOT NULL, accepted_at INTEGER NOT NULL DEFAULT 0, "
            "created_at INTEGER NOT NULL CHECK(created_at > 0), "
            "delivery_state TEXT NOT NULL, reply_target_message_id TEXT NOT NULL DEFAULT '', "
            "reply_target_sequence INTEGER NOT NULL DEFAULT 0, "
            "reply_target_sender_account_id TEXT NOT NULL DEFAULT '', "
            "PRIMARY KEY(account_id, conversation_id, client_message_id), "
            "FOREIGN KEY(account_id, conversation_id) REFERENCES v2_conversations"
            "(account_id, conversation_id) ON DELETE CASCADE, "
            "CHECK(delivery_state IN ('pending', 'failed', 'accepted')), "
            "CHECK((delivery_state = 'accepted' AND message_id <> '' AND "
            "conversation_sequence > 0 AND accepted_at > 0) OR "
            "(delivery_state <> 'accepted' AND message_id = '' AND "
            "conversation_sequence = 0 AND accepted_at = 0)), "
            "CHECK((reply_target_message_id = '' AND reply_target_sequence = 0 AND "
            "reply_target_sender_account_id = '') OR (reply_target_message_id <> '' AND "
            "reply_target_sequence > 0 AND reply_target_sender_account_id <> '')))"),
        QStringLiteral(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_v2_messages_server_identity "
            "ON v2_messages(account_id, message_id) WHERE message_id <> ''"),
        QStringLiteral(
            "CREATE INDEX IF NOT EXISTS idx_v2_messages_order ON v2_messages("
            "account_id, conversation_id, conversation_sequence, created_at)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_message_reactions ("
            "account_id TEXT NOT NULL, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, "
            "reaction INTEGER NOT NULL CHECK(reaction BETWEEN 1 AND 6), "
            "actor_account_id TEXT NOT NULL, PRIMARY KEY(account_id, message_id, reaction, actor_account_id), "
            "FOREIGN KEY(account_id, conversation_id) REFERENCES v2_conversations"
            "(account_id, conversation_id) ON DELETE CASCADE)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_reaction_commands ("
            "account_id TEXT NOT NULL, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, "
            "reaction INTEGER NOT NULL CHECK(reaction BETWEEN 1 AND 6), active INTEGER NOT NULL "
            "CHECK(active IN (0, 1)), client_operation_id TEXT NOT NULL, delivery_state TEXT NOT NULL "
            "CHECK(delivery_state IN ('pending', 'failed')), "
            "PRIMARY KEY(account_id, client_operation_id), FOREIGN KEY(account_id, conversation_id) "
            "REFERENCES v2_conversations(account_id, conversation_id) ON DELETE CASCADE)"),
        QStringLiteral(
            "CREATE INDEX IF NOT EXISTS idx_v2_reaction_commands_pending ON "
            "v2_reaction_commands(account_id, delivery_state, conversation_id)"),
    };
    for (const auto &statement : statements) {
        if (!query.exec(statement)) {
            m_database.rollback();
            return fail(QStringLiteral("migrate"), query.lastError().text());
        }
    }
    if (version < 2 && !query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0 "
            "CHECK(recalled IN (0, 1))"))) {
        m_database.rollback();
        return fail(QStringLiteral("migrate"), query.lastError().text());
    }
    if (!query.exec(QStringLiteral("PRAGMA user_version = 3"))) {
        m_database.rollback();
        return fail(QStringLiteral("migrate"), query.lastError().text());
    }
    if (!m_database.commit()) return fail(QStringLiteral("migrate"), m_database.lastError().text());
    m_lastError.clear();
    qInfo() << "[V2LocalStore] operation=initialize outcome=success schema=3";
    return true;
}

bool V2LocalMessageRepository::upsertPending(
        const QString &accountId, const Message &message) {
    if (!canonicalUuid(accountId) || message.senderAccountId != accountId
            || !validPending(message))
        return fail(QStringLiteral("upsertPending"), QStringLiteral("invalid pending message"));
    if (!m_database.transaction()) return fail(QStringLiteral("upsertPending"), m_database.lastError().text());
    if (!ensureConversation(accountId, message.conversationId, 0)) {
        m_database.rollback(); return false;
    }
    QSqlQuery existing(m_database);
    existing.prepare(QStringLiteral(
        "SELECT text_content, reply_target_message_id, reply_target_sequence, "
        "reply_target_sender_account_id, delivery_state, sender_account_id, "
        "sender_device_id, created_at FROM v2_messages "
        "WHERE account_id = ? AND conversation_id = ? AND client_message_id = ?"));
    existing.addBindValue(accountId); existing.addBindValue(message.conversationId);
    existing.addBindValue(message.clientMessageId);
    if (!existing.exec()) { m_database.rollback(); return fail(QStringLiteral("upsertPending"), existing.lastError().text()); }
    const bool exists = existing.next();
    if (exists) {
        const bool same = existing.value(0).toString() == message.text
            && existing.value(1).toString()
                == (message.hasReply ? message.reply.targetMessageId : QString())
            && existing.value(2).toLongLong()
                == (message.hasReply ? message.reply.targetConversationSequence : 0)
            && existing.value(3).toString()
                == (message.hasReply ? message.reply.targetSenderAccountId : QString())
            && existing.value(5).toString() == message.senderAccountId
            && existing.value(6).toString() == message.senderDeviceId
            && existing.value(7).toLongLong() == message.createdAtEpochMs;
        const bool accepted = existing.value(4).toString() == QStringLiteral("accepted");
        if (!same) {
            m_database.rollback();
            return fail(QStringLiteral("upsertPending"), QStringLiteral("idempotency conflict"));
        }
        if (accepted) {
            if (!m_database.commit())
                return fail(QStringLiteral("upsertPending"), m_database.lastError().text());
            m_lastError.clear();
            return true;
        }
        QSqlQuery retry(m_database);
        retry.prepare(QStringLiteral(
            "UPDATE v2_messages SET delivery_state = 'pending' WHERE account_id = ? "
            "AND conversation_id = ? AND client_message_id = ?"));
        retry.addBindValue(accountId); retry.addBindValue(message.conversationId);
        retry.addBindValue(message.clientMessageId);
        if (!retry.exec() || retry.numRowsAffected() != 1) {
            m_database.rollback();
            return fail(QStringLiteral("upsertPending"), retry.lastError().text());
        }
        if (!m_database.commit())
            return fail(QStringLiteral("upsertPending"), m_database.lastError().text());
        m_lastError.clear();
        return true;
    }
    existing.finish();
    if (!exists) {
        QSqlQuery count(m_database);
        count.prepare(QStringLiteral(
            "SELECT COUNT(*) FROM v2_messages WHERE account_id = ? "
            "AND delivery_state <> 'accepted'"));
        count.addBindValue(accountId);
        if (!count.exec() || !count.next()) {
            m_database.rollback();
            return fail(QStringLiteral("upsertPending"), count.lastError().text());
        }
        if (count.value(0).toInt() >= MaxUnacceptedPerAccount) {
            m_database.rollback();
            return fail(QStringLiteral("upsertPending"), QStringLiteral("outbox limit reached"));
        }
    }
    QSqlQuery remove(m_database);
    remove.prepare(QStringLiteral(
        "DELETE FROM v2_messages WHERE account_id = ? AND conversation_id = ? "
        "AND client_message_id = ? AND delivery_state <> 'accepted'"));
    remove.addBindValue(accountId); remove.addBindValue(message.conversationId);
    remove.addBindValue(message.clientMessageId);
    if (!remove.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertPending"), remove.lastError().text());
    }
    if (!insertMessage(accountId, message)) {
        m_database.rollback();
        return false;
    }
    if (!m_database.commit()) {
        m_database.rollback();
        return fail(QStringLiteral("upsertPending"), m_database.lastError().text());
    }
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::markFailed(
        const QString &accountId, const QString &conversationId,
        const QString &clientMessageId) {
    if (!canonicalUuid(accountId) || !canonicalUuid(conversationId)
            || !validIdentifier(clientMessageId)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE v2_messages SET delivery_state = 'failed' WHERE account_id = ? "
        "AND conversation_id = ? AND client_message_id = ? AND delivery_state = 'pending'"));
    query.addBindValue(accountId); query.addBindValue(conversationId); query.addBindValue(clientMessageId);
    if (!query.exec() || query.numRowsAffected() != 1)
        return fail(QStringLiteral("markFailed"), query.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::applyAccepted(
        const QString &accountId, const QString &conversationId,
        const QString &clientMessageId, const QString &messageId,
        qint64 conversationSequence, qint64 acceptedAtEpochMs) {
    if (!canonicalUuid(accountId) || !canonicalUuid(conversationId)
            || !validIdentifier(clientMessageId) || !canonicalUuid(messageId)
            || conversationSequence <= 0 || acceptedAtEpochMs <= 0) return false;
    if (!m_database.transaction())
        return fail(QStringLiteral("applyAccepted"), m_database.lastError().text());
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE v2_messages SET message_id = ?, conversation_sequence = ?, "
        "accepted_at = ?, delivery_state = 'accepted' WHERE account_id = ? "
        "AND conversation_id = ? AND client_message_id = ? AND "
        "(delivery_state <> 'accepted' OR (message_id = ? AND conversation_sequence = ?))"));
    query.addBindValue(messageId); query.addBindValue(conversationSequence);
    query.addBindValue(acceptedAtEpochMs); query.addBindValue(accountId);
    query.addBindValue(conversationId); query.addBindValue(clientMessageId);
    query.addBindValue(messageId); query.addBindValue(conversationSequence);
    if (!query.exec() || query.numRowsAffected() != 1) {
        m_database.rollback();
        return fail(QStringLiteral("applyAccepted"), query.lastError().text());
    }
    if (!ensureConversation(accountId, conversationId, conversationSequence)) {
        m_database.rollback(); return false;
    }
    if (!m_database.commit())
        return fail(QStringLiteral("applyAccepted"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::mergeServerMessage(
        const QString &accountId, const Message &message, qint64 cursor) {
    return mergeServerPage(accountId, message.conversationId, {message}, cursor);
}

bool V2LocalMessageRepository::mergeServerPage(
        const QString &accountId, const QString &conversationId,
        const QList<Message> &messages, qint64 nextCursor,
        const QStringList &recalledMessageIds,
        const QStringList &deletedMessageIds,
        const QList<ReactionChange> &reactionChanges) {
    if (!canonicalUuid(accountId) || !canonicalUuid(conversationId) || nextCursor < 0)
        return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid page identity"));
    qint64 previous = 0;
    for (const auto &message : messages) {
        if (!validAccepted(message) || message.conversationId != conversationId
                || message.conversationSequence <= previous
                || message.conversationSequence > nextCursor)
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid ordered page"));
        previous = message.conversationSequence;
    }
    for (const auto &messageId : recalledMessageIds)
        if (!canonicalUuid(messageId))
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid recall target"));
    for (const auto &messageId : deletedMessageIds)
        if (!canonicalUuid(messageId))
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid deletion target"));
    for (const auto &change : reactionChanges)
        if (!validReactionChange(change, true) || change.conversationId != conversationId
                || change.conversationSequence > nextCursor)
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid reaction change"));
    if (!m_database.transaction())
        return fail(QStringLiteral("mergeServerPage"), m_database.lastError().text());
    if (!ensureConversation(accountId, conversationId, nextCursor)) {
        m_database.rollback(); return false;
    }
    for (const auto &message : messages) {
        QSqlQuery remove(m_database);
        remove.prepare(QStringLiteral(
            "DELETE FROM v2_messages WHERE account_id = ? AND (message_id = ? OR "
            "(conversation_id = ? AND client_message_id = ?))"));
        remove.addBindValue(accountId); remove.addBindValue(message.messageId);
        remove.addBindValue(conversationId); remove.addBindValue(message.clientMessageId);
        if (!remove.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("mergeServerPage"), remove.lastError().text());
        }
        if (!insertMessage(accountId, message)) { m_database.rollback(); return false; }
    }
    QSqlQuery mutation(m_database);
    mutation.prepare(QStringLiteral(
        "UPDATE v2_messages SET text_content = '', recalled = 1 WHERE account_id = ? "
        "AND conversation_id = ? AND message_id = ?"));
    for (const auto &messageId : recalledMessageIds) {
        mutation.bindValue(0, accountId); mutation.bindValue(1, conversationId);
        mutation.bindValue(2, messageId);
        if (!mutation.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("mergeServerPage"), mutation.lastError().text());
        }
    }
    QSqlQuery deletion(m_database);
    deletion.prepare(QStringLiteral(
        "DELETE FROM v2_messages WHERE account_id = ? AND conversation_id = ? AND message_id = ?"));
    for (const auto &messageId : deletedMessageIds) {
        deletion.bindValue(0, accountId); deletion.bindValue(1, conversationId);
        deletion.bindValue(2, messageId);
        if (!deletion.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("mergeServerPage"), deletion.lastError().text());
        }
        QSqlQuery removeReactions(m_database);
        removeReactions.prepare(QStringLiteral(
            "DELETE FROM v2_message_reactions WHERE account_id = ? AND message_id = ?"));
        removeReactions.addBindValue(accountId); removeReactions.addBindValue(messageId);
        if (!removeReactions.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("mergeServerPage"), removeReactions.lastError().text());
        }
    }
    for (const auto &change : reactionChanges) {
        if (!applyReactionProjection(accountId, change)) {
            m_database.rollback(); return false;
        }
        QSqlQuery acknowledged(m_database);
        acknowledged.prepare(QStringLiteral(
            "DELETE FROM v2_reaction_commands WHERE account_id = ? AND client_operation_id = ?"));
        acknowledged.addBindValue(accountId); acknowledged.addBindValue(change.clientOperationId);
        if (!acknowledged.exec()) {
            m_database.rollback();
            return fail(QStringLiteral("mergeServerPage"), acknowledged.lastError().text());
        }
    }
    if (!pruneAccepted(accountId, conversationId)) { m_database.rollback(); return false; }
    if (!m_database.commit()) {
        m_database.rollback();
        return fail(QStringLiteral("mergeServerPage"), m_database.lastError().text());
    }
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::mergeLiveMessage(
        const QString &accountId, const Message &message) {
    if (!canonicalUuid(accountId) || !validAccepted(message))
        return fail(QStringLiteral("mergeLiveMessage"), QStringLiteral("invalid live message"));
    if (!m_database.transaction())
        return fail(QStringLiteral("mergeLiveMessage"), m_database.lastError().text());
    if (!ensureConversation(accountId, message.conversationId, 0)) {
        m_database.rollback(); return false;
    }
    QSqlQuery remove(m_database);
    remove.prepare(QStringLiteral(
        "DELETE FROM v2_messages WHERE account_id = ? AND (message_id = ? OR "
        "(conversation_id = ? AND client_message_id = ?))"));
    remove.addBindValue(accountId); remove.addBindValue(message.messageId);
    remove.addBindValue(message.conversationId); remove.addBindValue(message.clientMessageId);
    if (!remove.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("mergeLiveMessage"), remove.lastError().text());
    }
    if (!insertMessage(accountId, message)
            || !pruneAccepted(accountId, message.conversationId)) {
        m_database.rollback(); return false;
    }
    if (!m_database.commit()) {
        m_database.rollback();
        return fail(QStringLiteral("mergeLiveMessage"), m_database.lastError().text());
    }
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::stageReaction(
        const QString &accountId, const ReactionCommand &command) {
    if (!canonicalUuid(accountId) || !validReactionCommand(command))
        return fail(QStringLiteral("stageReaction"), QStringLiteral("invalid reaction command"));
    if (!m_database.transaction())
        return fail(QStringLiteral("stageReaction"), m_database.lastError().text());
    QSqlQuery target(m_database);
    target.prepare(QStringLiteral(
        "SELECT recalled FROM v2_messages WHERE account_id = ? AND conversation_id = ? "
        "AND message_id = ? AND delivery_state = 'accepted'"));
    target.addBindValue(accountId); target.addBindValue(command.conversationId);
    target.addBindValue(command.messageId);
    if (!target.exec() || !target.next() || target.value(0).toBool()) {
        m_database.rollback();
        return fail(QStringLiteral("stageReaction"), QStringLiteral("reaction target unavailable"));
    }
    QSqlQuery existing(m_database);
    existing.prepare(QStringLiteral(
        "SELECT conversation_id, message_id, reaction, active FROM v2_reaction_commands "
        "WHERE account_id = ? AND client_operation_id = ?"));
    existing.addBindValue(accountId); existing.addBindValue(command.clientOperationId);
    if (!existing.exec()) { m_database.rollback(); return fail(QStringLiteral("stageReaction"), existing.lastError().text()); }
    if (existing.next()) {
        const bool same = existing.value(0).toString() == command.conversationId
            && existing.value(1).toString() == command.messageId
            && existing.value(2).toInt() == static_cast<int>(command.reaction)
            && existing.value(3).toBool() == command.active;
        if (!same) { m_database.rollback(); return fail(QStringLiteral("stageReaction"), QStringLiteral("idempotency conflict")); }
        QSqlQuery retry(m_database);
        retry.prepare(QStringLiteral(
            "UPDATE v2_reaction_commands SET delivery_state = 'pending' "
            "WHERE account_id = ? AND client_operation_id = ?"));
        retry.addBindValue(accountId); retry.addBindValue(command.clientOperationId);
        if (!retry.exec()) { m_database.rollback(); return fail(QStringLiteral("stageReaction"), retry.lastError().text()); }
    } else {
        QSqlQuery count(m_database);
        count.prepare(QStringLiteral(
            "SELECT COUNT(*) FROM v2_reaction_commands WHERE account_id = ?"));
        count.addBindValue(accountId);
        if (!count.exec() || !count.next()
                || count.value(0).toInt() >= MaxPendingReactionsPerAccount) {
            m_database.rollback();
            return fail(QStringLiteral("stageReaction"), QStringLiteral("reaction outbox limit reached"));
        }
        QSqlQuery insert(m_database);
        insert.prepare(QStringLiteral(
            "INSERT INTO v2_reaction_commands(account_id, conversation_id, message_id, reaction, "
            "active, client_operation_id, delivery_state) VALUES(?, ?, ?, ?, ?, ?, 'pending')"));
        insert.addBindValue(accountId); insert.addBindValue(command.conversationId);
        insert.addBindValue(command.messageId); insert.addBindValue(static_cast<int>(command.reaction));
        insert.addBindValue(command.active); insert.addBindValue(command.clientOperationId);
        if (!insert.exec()) { m_database.rollback(); return fail(QStringLiteral("stageReaction"), insert.lastError().text()); }
    }
    ReactionChange optimistic{command.conversationId, 0, command.messageId,
        command.reaction, command.active, accountId, command.clientOperationId, 1};
    if (!applyReactionProjection(accountId, optimistic)) { m_database.rollback(); return false; }
    if (!m_database.commit()) return fail(QStringLiteral("stageReaction"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::markReactionFailed(
        const QString &accountId, const QString &clientOperationId) {
    if (!canonicalUuid(accountId) || !validIdentifier(clientOperationId)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE v2_reaction_commands SET delivery_state = 'failed' WHERE account_id = ? "
        "AND client_operation_id = ? AND delivery_state = 'pending'"));
    query.addBindValue(accountId); query.addBindValue(clientOperationId);
    if (!query.exec() || query.numRowsAffected() != 1)
        return fail(QStringLiteral("markReactionFailed"), query.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::applyReaction(
        const QString &accountId, const ReactionChange &change) {
    if (!canonicalUuid(accountId) || !validReactionChange(change, false))
        return fail(QStringLiteral("applyReaction"), QStringLiteral("invalid reaction result"));
    if (!m_database.transaction()) return fail(QStringLiteral("applyReaction"), m_database.lastError().text());
    if (!applyReactionProjection(accountId, change)) { m_database.rollback(); return false; }
    QSqlQuery command(m_database);
    command.prepare(QStringLiteral(
        "DELETE FROM v2_reaction_commands WHERE account_id = ? AND client_operation_id = ?"));
    command.addBindValue(accountId); command.addBindValue(change.clientOperationId);
    if (!command.exec()) {
        m_database.rollback(); return fail(QStringLiteral("applyReaction"), command.lastError().text());
    }
    if (!m_database.commit()) return fail(QStringLiteral("applyReaction"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::mergeLiveReaction(
        const QString &accountId, const ReactionChange &change) {
    if (!canonicalUuid(accountId) || !validReactionChange(change, true))
        return fail(QStringLiteral("mergeLiveReaction"), QStringLiteral("invalid live reaction"));
    if (!m_database.transaction()) return fail(QStringLiteral("mergeLiveReaction"), m_database.lastError().text());
    if (!applyReactionProjection(accountId, change)) { m_database.rollback(); return false; }
    QSqlQuery command(m_database);
    command.prepare(QStringLiteral(
        "DELETE FROM v2_reaction_commands WHERE account_id = ? AND client_operation_id = ?"));
    command.addBindValue(accountId); command.addBindValue(change.clientOperationId);
    if (!command.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeLiveReaction"), command.lastError().text()); }
    if (!m_database.commit()) return fail(QStringLiteral("mergeLiveReaction"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::saveDraft(
        const QString &accountId, const QString &conversationId, const QString &draft) {
    if (!canonicalUuid(accountId) || !canonicalUuid(conversationId)
            || !ensureConversation(accountId, conversationId, 0)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "UPDATE v2_conversations SET draft = ?, updated_at = ? "
        "WHERE account_id = ? AND conversation_id = ?"));
    query.addBindValue(draft.left(MaxDraftLength));
    query.addBindValue(QDateTime::currentMSecsSinceEpoch());
    query.addBindValue(accountId); query.addBindValue(conversationId);
    if (!query.exec()) return fail(QStringLiteral("saveDraft"), query.lastError().text());
    m_lastError.clear(); return true;
}

V2LocalMessageRepository::Snapshot V2LocalMessageRepository::loadSnapshot(
        const QString &accountId, const QString &conversationId) {
    Snapshot result;
    if (!canonicalUuid(accountId) || !canonicalUuid(conversationId)) return result;
    QSqlQuery conversation(m_database);
    conversation.prepare(QStringLiteral(
        "SELECT cursor, draft FROM v2_conversations WHERE account_id = ? AND conversation_id = ?"));
    conversation.addBindValue(accountId); conversation.addBindValue(conversationId);
    if (!conversation.exec()) { fail(QStringLiteral("loadSnapshot"), conversation.lastError().text()); return {}; }
    if (!conversation.next()) { m_lastError.clear(); return result; }
    result.cursor = conversation.value(0).toLongLong(); result.draft = conversation.value(1).toString();
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT message_id, conversation_sequence, sender_account_id, sender_device_id, "
        "client_message_id, text_content, accepted_at, created_at, delivery_state, "
        "reply_target_message_id, reply_target_sequence, reply_target_sender_account_id, recalled "
        "FROM v2_messages WHERE account_id = ? AND conversation_id = ? "
        "ORDER BY CASE WHEN conversation_sequence = 0 THEN 1 ELSE 0 END, "
        "conversation_sequence, created_at, client_message_id"));
    query.addBindValue(accountId); query.addBindValue(conversationId);
    if (!query.exec()) { fail(QStringLiteral("loadSnapshot"), query.lastError().text()); return {}; }
    while (query.next()) {
        Message message;
        message.conversationId = conversationId; message.messageId = query.value(0).toString();
        message.conversationSequence = query.value(1).toLongLong();
        message.senderAccountId = query.value(2).toString(); message.senderDeviceId = query.value(3).toString();
        message.clientMessageId = query.value(4).toString(); message.text = query.value(5).toString();
        message.acceptedAtEpochMs = query.value(6).toLongLong(); message.createdAtEpochMs = query.value(7).toLongLong();
        if (!parseState(query.value(8).toString(), &message.state)) continue;
        message.reply.targetMessageId = query.value(9).toString();
        message.reply.targetConversationSequence = query.value(10).toLongLong();
        message.reply.targetSenderAccountId = query.value(11).toString();
        message.recalled = query.value(12).toBool();
        message.hasReply = !message.reply.targetMessageId.isEmpty();
        result.messages.append(message);
    }
    QSqlQuery reactions(m_database);
    reactions.prepare(QStringLiteral(
        "SELECT message_id, reaction, actor_account_id FROM v2_message_reactions "
        "WHERE account_id = ? AND conversation_id = ? ORDER BY message_id, reaction, actor_account_id"));
    reactions.addBindValue(accountId); reactions.addBindValue(conversationId);
    if (!reactions.exec()) { fail(QStringLiteral("loadSnapshot"), reactions.lastError().text()); return {}; }
    while (reactions.next()) {
        const QString messageId = reactions.value(0).toString();
        const auto kind = static_cast<ReactionKind>(reactions.value(1).toInt());
        auto message = std::find_if(result.messages.begin(), result.messages.end(),
            [&](const Message &value) { return value.messageId == messageId; });
        if (message == result.messages.end() || !validReactionKind(kind)) continue;
        auto aggregate = std::find_if(message->reactions.begin(), message->reactions.end(),
            [&](const ReactionAggregate &value) { return value.reaction == kind; });
        if (aggregate == message->reactions.end()) {
            message->reactions.append({kind, {reactions.value(2).toString()}});
        } else {
            aggregate->actorAccountIds.append(reactions.value(2).toString());
        }
    }
    QSqlQuery commands(m_database);
    commands.prepare(QStringLiteral(
        "SELECT message_id, reaction, active, client_operation_id, delivery_state "
        "FROM v2_reaction_commands WHERE account_id = ? AND conversation_id = ? "
        "ORDER BY rowid"));
    commands.addBindValue(accountId); commands.addBindValue(conversationId);
    if (!commands.exec()) { fail(QStringLiteral("loadSnapshot"), commands.lastError().text()); return {}; }
    while (commands.next()) {
        ReactionCommand command;
        command.conversationId = conversationId;
        command.messageId = commands.value(0).toString();
        command.reaction = static_cast<ReactionKind>(commands.value(1).toInt());
        command.active = commands.value(2).toBool();
        command.clientOperationId = commands.value(3).toString();
        command.state = commands.value(4).toString() == QStringLiteral("failed")
            ? DeliveryState::Failed : DeliveryState::Pending;
        if (validReactionCommand(command)) result.reactionCommands.append(command);
    }
    m_lastError.clear(); return result;
}

QList<V2LocalMessageRepository::Message>
V2LocalMessageRepository::pendingSends(const QString &accountId) {
    QList<Message> result;
    if (!canonicalUuid(accountId)) return result;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT DISTINCT conversation_id FROM v2_messages WHERE account_id = ? "
        "AND delivery_state = 'pending' ORDER BY conversation_id"));
    query.addBindValue(accountId);
    if (!query.exec()) { fail(QStringLiteral("pendingSends"), query.lastError().text()); return {}; }
    QStringList conversations;
    while (query.next()) conversations.append(query.value(0).toString());
    for (const auto &conversation : conversations) {
        const auto snapshot = loadSnapshot(accountId, conversation);
        for (const auto &message : snapshot.messages)
            if (message.state == DeliveryState::Pending) result.append(message);
    }
    m_lastError.clear(); return result;
}

QList<V2LocalMessageRepository::ReactionCommand>
V2LocalMessageRepository::pendingReactions(const QString &accountId) {
    QList<ReactionCommand> result;
    if (!canonicalUuid(accountId)) return result;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "SELECT conversation_id, message_id, reaction, active, client_operation_id "
        "FROM v2_reaction_commands WHERE account_id = ? AND delivery_state = 'pending' "
        "ORDER BY rowid"));
    query.addBindValue(accountId);
    if (!query.exec()) { fail(QStringLiteral("pendingReactions"), query.lastError().text()); return {}; }
    while (query.next()) {
        ReactionCommand command;
        command.conversationId = query.value(0).toString(); command.messageId = query.value(1).toString();
        command.reaction = static_cast<ReactionKind>(query.value(2).toInt());
        command.active = query.value(3).toBool(); command.clientOperationId = query.value(4).toString();
        if (validReactionCommand(command)) result.append(command);
    }
    m_lastError.clear(); return result;
}

bool V2LocalMessageRepository::ensureConversation(
        const QString &accountId, const QString &conversationId, qint64 cursor) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO v2_conversations(account_id, conversation_id, cursor, updated_at) "
        "VALUES(?, ?, ?, ?) ON CONFLICT(account_id, conversation_id) DO UPDATE SET "
        "cursor = MAX(cursor, excluded.cursor), updated_at = excluded.updated_at"));
    query.addBindValue(accountId); query.addBindValue(conversationId); query.addBindValue(cursor);
    query.addBindValue(QDateTime::currentMSecsSinceEpoch());
    return query.exec() || fail(QStringLiteral("ensureConversation"), query.lastError().text());
}

bool V2LocalMessageRepository::insertMessage(const QString &accountId, const Message &message) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO v2_messages(account_id, conversation_id, client_message_id, message_id, "
        "conversation_sequence, sender_account_id, sender_device_id, text_content, accepted_at, "
        "created_at, delivery_state, reply_target_message_id, reply_target_sequence, "
        "reply_target_sender_account_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
    query.addBindValue(accountId); query.addBindValue(message.conversationId);
    query.addBindValue(message.clientMessageId);
    query.addBindValue(message.messageId.isNull() ? QStringLiteral("") : message.messageId);
    query.addBindValue(message.conversationSequence); query.addBindValue(message.senderAccountId);
    query.addBindValue(message.senderDeviceId); query.addBindValue(message.text);
    query.addBindValue(message.acceptedAtEpochMs); query.addBindValue(message.createdAtEpochMs);
    query.addBindValue(stateValue(message.state));
    query.addBindValue(message.hasReply ? message.reply.targetMessageId : QStringLiteral(""));
    query.addBindValue(message.hasReply ? message.reply.targetConversationSequence : 0);
    query.addBindValue(message.hasReply
        ? message.reply.targetSenderAccountId : QStringLiteral(""));
    return query.exec() || fail(QStringLiteral("insertMessage"), query.lastError().text());
}

bool V2LocalMessageRepository::pruneAccepted(
        const QString &accountId, const QString &conversationId) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "DELETE FROM v2_messages WHERE rowid IN (SELECT rowid FROM v2_messages "
        "WHERE account_id = ? AND conversation_id = ? AND delivery_state = 'accepted' "
        "ORDER BY conversation_sequence DESC LIMIT -1 OFFSET ?)"));
    query.addBindValue(accountId); query.addBindValue(conversationId);
    query.addBindValue(MaxMessagesPerConversation);
    if (!query.exec()) return fail(QStringLiteral("pruneAccepted"), query.lastError().text());
    QSqlQuery orphaned(m_database);
    orphaned.prepare(QStringLiteral(
        "DELETE FROM v2_message_reactions WHERE account_id = ? AND conversation_id = ? "
        "AND NOT EXISTS (SELECT 1 FROM v2_messages m WHERE m.account_id = ? "
        "AND m.conversation_id = ? AND m.message_id = v2_message_reactions.message_id)"));
    orphaned.addBindValue(accountId); orphaned.addBindValue(conversationId);
    orphaned.addBindValue(accountId); orphaned.addBindValue(conversationId);
    return orphaned.exec() || fail(QStringLiteral("pruneAccepted"), orphaned.lastError().text());
}

bool V2LocalMessageRepository::applyReactionProjection(
        const QString &accountId, const ReactionChange &change) {
    QSqlQuery query(m_database);
    if (change.active) {
        query.prepare(QStringLiteral(
            "INSERT OR IGNORE INTO v2_message_reactions(account_id, conversation_id, message_id, "
            "reaction, actor_account_id) SELECT ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM "
            "v2_messages WHERE account_id = ? AND conversation_id = ? AND message_id = ?)"));
        query.addBindValue(accountId); query.addBindValue(change.conversationId);
        query.addBindValue(change.messageId); query.addBindValue(static_cast<int>(change.reaction));
        query.addBindValue(change.actorAccountId); query.addBindValue(accountId);
        query.addBindValue(change.conversationId); query.addBindValue(change.messageId);
    } else {
        query.prepare(QStringLiteral(
            "DELETE FROM v2_message_reactions WHERE account_id = ? AND conversation_id = ? "
            "AND message_id = ? AND reaction = ? AND actor_account_id = ?"));
        query.addBindValue(accountId); query.addBindValue(change.conversationId);
        query.addBindValue(change.messageId); query.addBindValue(static_cast<int>(change.reaction));
        query.addBindValue(change.actorAccountId);
    }
    return query.exec() || fail(QStringLiteral("applyReactionProjection"), query.lastError().text());
}

bool V2LocalMessageRepository::canonicalUuid(const QString &value) {
    const QUuid uuid(value);
    return !uuid.isNull() && uuid.toString(QUuid::WithoutBraces) == value;
}
bool V2LocalMessageRepository::validIdentifier(const QString &value) {
    QStringEncoder encoder(QStringEncoder::Utf8);
    const QByteArray bytes = encoder(value);
    return !encoder.hasError() && !value.trimmed().isEmpty() && bytes.size() <= 128;
}
QString V2LocalMessageRepository::stateValue(DeliveryState state) {
    switch (state) {
    case DeliveryState::Pending: return QStringLiteral("pending");
    case DeliveryState::Failed: return QStringLiteral("failed");
    case DeliveryState::Accepted: return QStringLiteral("accepted");
    }
    return {};
}
bool V2LocalMessageRepository::parseState(const QString &value, DeliveryState *state) {
    if (!state) return false;
    if (value == QStringLiteral("pending")) *state = DeliveryState::Pending;
    else if (value == QStringLiteral("failed")) *state = DeliveryState::Failed;
    else if (value == QStringLiteral("accepted")) *state = DeliveryState::Accepted;
    else return false;
    return true;
}
bool V2LocalMessageRepository::validBaseMessage(const Message &message) {
    QStringEncoder encoder(QStringEncoder::Utf8);
    const QByteArray text = encoder(message.text);
    if (!canonicalUuid(message.conversationId) || !canonicalUuid(message.senderAccountId)
            || !canonicalUuid(message.senderDeviceId) || !validIdentifier(message.clientMessageId)
            || encoder.hasError() || text.isEmpty() || text.size() > MaxTextBytes
            || message.createdAtEpochMs <= 0) return false;
    return !message.hasReply || (canonicalUuid(message.reply.targetMessageId)
        && message.reply.targetConversationSequence > 0
        && canonicalUuid(message.reply.targetSenderAccountId));
}
bool V2LocalMessageRepository::validPending(const Message &message) {
    return validBaseMessage(message) && message.messageId.isEmpty()
        && message.conversationSequence == 0 && message.acceptedAtEpochMs == 0
        && message.state == DeliveryState::Pending;
}
bool V2LocalMessageRepository::validAccepted(const Message &message) {
    return validBaseMessage(message) && canonicalUuid(message.messageId)
        && message.conversationSequence > 0 && message.acceptedAtEpochMs > 0
        && message.state == DeliveryState::Accepted && !message.recalled
        && (!message.hasReply
            || message.reply.targetConversationSequence < message.conversationSequence);
}
bool V2LocalMessageRepository::validReactionKind(ReactionKind reaction) {
    const int value = static_cast<int>(reaction);
    return value >= static_cast<int>(ReactionKind::Like)
        && value <= static_cast<int>(ReactionKind::Angry);
}
bool V2LocalMessageRepository::validReactionChange(
        const ReactionChange &change, bool sequenceRequired) {
    return canonicalUuid(change.conversationId) && canonicalUuid(change.messageId)
        && validReactionKind(change.reaction) && canonicalUuid(change.actorAccountId)
        && validIdentifier(change.clientOperationId) && change.occurredAtEpochMs > 0
        && (sequenceRequired ? change.conversationSequence > 0
                             : change.conversationSequence >= 0);
}
bool V2LocalMessageRepository::validReactionCommand(const ReactionCommand &command) {
    return canonicalUuid(command.conversationId) && canonicalUuid(command.messageId)
        && validReactionKind(command.reaction) && validIdentifier(command.clientOperationId)
        && command.state != DeliveryState::Accepted;
}
bool V2LocalMessageRepository::fail(const QString &operation, const QString &detail) {
    m_lastError = operation + QStringLiteral(": ") + detail;
    qWarning().noquote() << QStringLiteral("[V2LocalStore] operation=%1 outcome=failure").arg(operation);
    return false;
}
