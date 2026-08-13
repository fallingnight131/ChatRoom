#include "V2LocalMessageRepository.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QSqlError>
#include <QSqlQuery>
#include <QSet>
#include <QStandardPaths>
#include <QStringEncoder>
#include <QUuid>
#include <QDebug>

namespace { constexpr int SchemaVersion = 7; }

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
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_message_pins (account_id TEXT NOT NULL, "
            "conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(account_id, "
            "conversation_id, message_id), FOREIGN KEY(account_id, conversation_id) REFERENCES "
            "v2_conversations(account_id, conversation_id) ON DELETE CASCADE)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_pin_commands (account_id TEXT NOT NULL, "
            "conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, pinned INTEGER NOT NULL "
            "CHECK(pinned IN (0,1)), client_operation_id TEXT NOT NULL, delivery_state TEXT NOT NULL "
            "CHECK(delivery_state IN ('pending','failed')), PRIMARY KEY(account_id, client_operation_id), "
            "FOREIGN KEY(account_id, conversation_id) REFERENCES v2_conversations(account_id, conversation_id) ON DELETE CASCADE)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS idx_v2_pin_commands_pending ON "
            "v2_pin_commands(account_id, delivery_state, conversation_id)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_edit_commands (account_id TEXT NOT NULL, "
            "conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, "
            "expected_revision INTEGER NOT NULL CHECK(expected_revision BETWEEN 0 AND 100), "
            "proposed_text TEXT NOT NULL, client_operation_id TEXT NOT NULL, "
            "delivery_state TEXT NOT NULL CHECK(delivery_state IN ('pending','failed','conflict')), "
            "PRIMARY KEY(account_id, client_operation_id), "
            "UNIQUE(account_id, conversation_id, message_id), "
            "FOREIGN KEY(account_id, conversation_id) REFERENCES v2_conversations"
            "(account_id, conversation_id) ON DELETE CASCADE)"),
        QStringLiteral("CREATE INDEX IF NOT EXISTS idx_v2_edit_commands_pending ON "
            "v2_edit_commands(account_id, delivery_state, conversation_id)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_message_mentions ("
            "account_id TEXT NOT NULL, conversation_id TEXT NOT NULL, "
            "client_message_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 19), "
            "target_account_id TEXT NOT NULL, start_utf8_byte INTEGER NOT NULL CHECK(start_utf8_byte >= 0), "
            "length_utf8_bytes INTEGER NOT NULL CHECK(length_utf8_bytes > 0), "
            "PRIMARY KEY(account_id, conversation_id, client_message_id, ordinal), "
            "FOREIGN KEY(account_id, conversation_id, client_message_id) REFERENCES v2_messages"
            "(account_id, conversation_id, client_message_id) ON DELETE CASCADE)"),
        QStringLiteral(
            "CREATE TABLE IF NOT EXISTS v2_edit_command_mentions ("
            "account_id TEXT NOT NULL, client_operation_id TEXT NOT NULL, "
            "ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 19), "
            "target_account_id TEXT NOT NULL, start_utf8_byte INTEGER NOT NULL CHECK(start_utf8_byte >= 0), "
            "length_utf8_bytes INTEGER NOT NULL CHECK(length_utf8_bytes > 0), "
            "PRIMARY KEY(account_id, client_operation_id, ordinal), "
            "FOREIGN KEY(account_id, client_operation_id) REFERENCES v2_edit_commands"
            "(account_id, client_operation_id) ON DELETE CASCADE ON UPDATE CASCADE)"),
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
    if (version < 5 && (!query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 0 "
            "CHECK(content_revision BETWEEN 0 AND 100)"))
            || !query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0 "
            "CHECK((content_revision = 0 AND edited_at = 0) OR "
            "(content_revision > 0 AND edited_at > 0))")))) {
        m_database.rollback();
        return fail(QStringLiteral("migrate"), query.lastError().text());
    }
    if (version < 7 && (!query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 "
            "CHECK(forwarded IN (0,1))"))
            || !query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN forward_source_conversation_id TEXT NOT NULL DEFAULT ''"))
            || !query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN forward_source_message_id TEXT NOT NULL DEFAULT ''"))
            || !query.exec(QStringLiteral(
            "ALTER TABLE v2_messages ADD COLUMN forward_source_revision INTEGER NOT NULL DEFAULT 0 "
            "CHECK(forward_source_revision BETWEEN 0 AND 100)")))) {
        m_database.rollback();
        return fail(QStringLiteral("migrate"), query.lastError().text());
    }
    if (!query.exec(QStringLiteral("PRAGMA user_version = 7"))) {
        m_database.rollback();
        return fail(QStringLiteral("migrate"), query.lastError().text());
    }
    if (!m_database.commit()) return fail(QStringLiteral("migrate"), m_database.lastError().text());
    m_lastError.clear();
    qInfo() << "[V2LocalStore] operation=initialize outcome=success schema=7";
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
        "sender_device_id, created_at, forwarded, forward_source_conversation_id, "
        "forward_source_message_id, forward_source_revision FROM v2_messages "
        "WHERE account_id = ? AND conversation_id = ? AND client_message_id = ?"));
    existing.addBindValue(accountId); existing.addBindValue(message.conversationId);
    existing.addBindValue(message.clientMessageId);
    if (!existing.exec()) { m_database.rollback(); return fail(QStringLiteral("upsertPending"), existing.lastError().text()); }
    const bool exists = existing.next();
    if (exists) {
        QList<Mention> storedMentions;
        QSqlQuery mentionQuery(m_database);
        mentionQuery.prepare(QStringLiteral("SELECT target_account_id,start_utf8_byte,"
            "length_utf8_bytes FROM v2_message_mentions WHERE account_id=? AND conversation_id=? "
            "AND client_message_id=? ORDER BY ordinal"));
        mentionQuery.addBindValue(accountId); mentionQuery.addBindValue(message.conversationId);
        mentionQuery.addBindValue(message.clientMessageId);
        if (!mentionQuery.exec()) { m_database.rollback(); return fail(QStringLiteral("upsertPending"), mentionQuery.lastError().text()); }
        while (mentionQuery.next()) storedMentions.append({mentionQuery.value(0).toString(),
            mentionQuery.value(1).toInt(), mentionQuery.value(2).toInt()});
        const bool accepted = existing.value(4).toString() == QStringLiteral("accepted");
        const bool same = (accepted || existing.value(0).toString() == message.text)
            && existing.value(1).toString()
                == (message.hasReply ? message.reply.targetMessageId : QString())
            && existing.value(2).toLongLong()
                == (message.hasReply ? message.reply.targetConversationSequence : 0)
            && existing.value(3).toString()
                == (message.hasReply ? message.reply.targetSenderAccountId : QString())
            && existing.value(5).toString() == message.senderAccountId
            && existing.value(6).toString() == message.senderDeviceId
            && existing.value(7).toLongLong() == message.createdAtEpochMs
            && existing.value(8).toBool() == message.forwarded
            && (accepted || (existing.value(9).toString() == message.forwardSourceConversationId
                && existing.value(10).toString() == message.forwardSourceMessageId
                && existing.value(11).toInt() == message.expectedForwardSourceRevision))
            && sameMentions(storedMentions, message.mentions);
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
        "accepted_at = ?, delivery_state = 'accepted', forward_source_conversation_id = '', "
        "forward_source_message_id = '', forward_source_revision = 0 WHERE account_id = ? "
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
        const QList<ReactionChange> &reactionChanges,
        const QList<PinChange> &pinChanges,
        const QList<EditChange> &editChanges) {
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
    for (const auto &change : pinChanges)
        if (!validPinChange(change, true) || change.conversationId != conversationId
                || change.conversationSequence > nextCursor)
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid pin change"));
    for (const auto &change : editChanges)
        if (!validEditChange(change, true) || change.conversationId != conversationId
                || change.conversationSequence > nextCursor)
            return fail(QStringLiteral("mergeServerPage"), QStringLiteral("invalid edit change"));
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
        QSqlQuery removeMentions(m_database);
        removeMentions.prepare(QStringLiteral(
            "DELETE FROM v2_message_mentions WHERE account_id=? AND conversation_id=? "
            "AND client_message_id IN (SELECT client_message_id FROM v2_messages WHERE "
            "account_id=? AND conversation_id=? AND message_id=?)"));
        removeMentions.addBindValue(accountId); removeMentions.addBindValue(conversationId);
        removeMentions.addBindValue(accountId); removeMentions.addBindValue(conversationId);
        removeMentions.addBindValue(messageId);
        if (!removeMentions.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), removeMentions.lastError().text()); }
        QSqlQuery cleanup(m_database);
        cleanup.prepare(QStringLiteral("DELETE FROM v2_message_pins WHERE account_id=? AND conversation_id=? AND message_id=?"));
        cleanup.addBindValue(accountId); cleanup.addBindValue(conversationId); cleanup.addBindValue(messageId);
        if (!cleanup.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), cleanup.lastError().text()); }
        QSqlQuery commands(m_database);
        commands.prepare(QStringLiteral("DELETE FROM v2_pin_commands WHERE account_id=? AND conversation_id=? AND message_id=?"));
        commands.addBindValue(accountId); commands.addBindValue(conversationId); commands.addBindValue(messageId);
        if (!commands.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), commands.lastError().text()); }
        QSqlQuery edits(m_database);
        edits.prepare(QStringLiteral("DELETE FROM v2_edit_commands WHERE account_id=? AND conversation_id=? AND message_id=?"));
        edits.addBindValue(accountId); edits.addBindValue(conversationId); edits.addBindValue(messageId);
        if (!edits.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), edits.lastError().text()); }
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
        QSqlQuery removePins(m_database);
        removePins.prepare(QStringLiteral("DELETE FROM v2_message_pins WHERE account_id=? AND conversation_id=? AND message_id=?"));
        removePins.addBindValue(accountId); removePins.addBindValue(conversationId); removePins.addBindValue(messageId);
        if (!removePins.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), removePins.lastError().text()); }
        QSqlQuery removePinCommands(m_database);
        removePinCommands.prepare(QStringLiteral("DELETE FROM v2_pin_commands WHERE account_id=? AND conversation_id=? AND message_id=?"));
        removePinCommands.addBindValue(accountId); removePinCommands.addBindValue(conversationId); removePinCommands.addBindValue(messageId);
        if (!removePinCommands.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), removePinCommands.lastError().text()); }
        QSqlQuery removeEditCommands(m_database);
        removeEditCommands.prepare(QStringLiteral("DELETE FROM v2_edit_commands WHERE account_id=? AND conversation_id=? AND message_id=?"));
        removeEditCommands.addBindValue(accountId); removeEditCommands.addBindValue(conversationId); removeEditCommands.addBindValue(messageId);
        if (!removeEditCommands.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), removeEditCommands.lastError().text()); }
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
    for (const auto &change : pinChanges) {
        if (!applyPinProjection(accountId, change)) { m_database.rollback(); return false; }
        QSqlQuery acknowledged(m_database);
        acknowledged.prepare(QStringLiteral("DELETE FROM v2_pin_commands WHERE account_id=? AND client_operation_id=?"));
        acknowledged.addBindValue(accountId); acknowledged.addBindValue(change.clientOperationId);
        if (!acknowledged.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), acknowledged.lastError().text()); }
    }
    for (const auto &change : editChanges) {
        if (!applyEditProjection(accountId, change)) { m_database.rollback(); return false; }
        QSqlQuery acknowledged(m_database);
        acknowledged.prepare(QStringLiteral("DELETE FROM v2_edit_commands WHERE account_id=? AND client_operation_id=?"));
        acknowledged.addBindValue(accountId); acknowledged.addBindValue(change.clientOperationId);
        if (!acknowledged.exec()) { m_database.rollback(); return fail(QStringLiteral("mergeServerPage"), acknowledged.lastError().text()); }
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

bool V2LocalMessageRepository::stagePin(const QString &accountId, const PinCommand &command) {
    if (!canonicalUuid(accountId) || !validPinCommand(command))
        return fail(QStringLiteral("stagePin"), QStringLiteral("invalid pin command"));
    if (!m_database.transaction()) return fail(QStringLiteral("stagePin"), m_database.lastError().text());
    if (!ensureConversation(accountId, command.conversationId, 0)) { m_database.rollback(); return false; }
    QSqlQuery target(m_database);
    target.prepare(QStringLiteral("SELECT 1 FROM v2_messages WHERE account_id=? AND conversation_id=? AND message_id=? AND delivery_state='accepted' AND recalled=0"));
    target.addBindValue(accountId); target.addBindValue(command.conversationId); target.addBindValue(command.messageId);
    if (!target.exec() || !target.next()) { m_database.rollback(); return fail(QStringLiteral("stagePin"), QStringLiteral("pin target unavailable")); }
    QSqlQuery existing(m_database);
    existing.prepare(QStringLiteral("SELECT conversation_id,message_id,pinned FROM v2_pin_commands WHERE account_id=? AND client_operation_id=?"));
    existing.addBindValue(accountId); existing.addBindValue(command.clientOperationId);
    if (!existing.exec()) { m_database.rollback(); return fail(QStringLiteral("stagePin"), existing.lastError().text()); }
    if (existing.next()) {
        if (existing.value(0).toString()!=command.conversationId || existing.value(1).toString()!=command.messageId || existing.value(2).toBool()!=command.pinned) {
            m_database.rollback(); return fail(QStringLiteral("stagePin"), QStringLiteral("idempotency conflict"));
        }
        QSqlQuery retry(m_database); retry.prepare(QStringLiteral("UPDATE v2_pin_commands SET delivery_state='pending' WHERE account_id=? AND client_operation_id=?"));
        retry.addBindValue(accountId); retry.addBindValue(command.clientOperationId);
        if (!retry.exec()) { m_database.rollback(); return fail(QStringLiteral("stagePin"), retry.lastError().text()); }
    } else {
        QSqlQuery count(m_database); count.prepare(QStringLiteral("SELECT COUNT(*) FROM v2_pin_commands WHERE account_id=?"));
        count.addBindValue(accountId);
        if (!count.exec() || !count.next() || count.value(0).toInt() >= MaxPendingPinsPerAccount) {
            m_database.rollback(); return fail(QStringLiteral("stagePin"), QStringLiteral("pin outbox limit reached"));
        }
        QSqlQuery insert(m_database); insert.prepare(QStringLiteral("INSERT INTO v2_pin_commands(account_id,conversation_id,message_id,pinned,client_operation_id,delivery_state) VALUES(?,?,?,?,?,'pending')"));
        insert.addBindValue(accountId); insert.addBindValue(command.conversationId); insert.addBindValue(command.messageId); insert.addBindValue(command.pinned); insert.addBindValue(command.clientOperationId);
        if (!insert.exec()) { m_database.rollback(); return fail(QStringLiteral("stagePin"), insert.lastError().text()); }
    }
    PinChange optimistic{command.conversationId,0,command.messageId,command.pinned,accountId,command.clientOperationId,1};
    if (!applyPinProjection(accountId, optimistic)) { m_database.rollback(); return false; }
    if (!m_database.commit()) return fail(QStringLiteral("stagePin"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::markPinFailed(const QString &accountId, const QString &operationId) {
    if (!canonicalUuid(accountId) || !validIdentifier(operationId))
        return fail(QStringLiteral("markPinFailed"), QStringLiteral("invalid pin operation"));
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral("UPDATE v2_pin_commands SET delivery_state='failed' WHERE account_id=? AND client_operation_id=? AND delivery_state='pending'"));
    query.addBindValue(accountId); query.addBindValue(operationId);
    if (!query.exec() || query.numRowsAffected() != 1)
        return fail(QStringLiteral("markPinFailed"), query.lastError().text());
    m_lastError.clear();
    return true;
}

bool V2LocalMessageRepository::applyPin(const QString &accountId, const PinChange &change) {
    if (!canonicalUuid(accountId) || !validPinChange(change, false))
        return fail(QStringLiteral("applyPin"), QStringLiteral("invalid pin result"));
    if (!m_database.transaction())
        return fail(QStringLiteral("applyPin"), m_database.lastError().text());
    if (!applyPinProjection(accountId, change)) { m_database.rollback(); return false; }
    QSqlQuery command(m_database); command.prepare(QStringLiteral("DELETE FROM v2_pin_commands WHERE account_id=? AND client_operation_id=?"));
    command.addBindValue(accountId); command.addBindValue(change.clientOperationId);
    if (!command.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("applyPin"), command.lastError().text());
    }
    if (!m_database.commit())
        return fail(QStringLiteral("applyPin"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::mergeLivePin(const QString &accountId, const PinChange &change) {
    if (!canonicalUuid(accountId) || !validPinChange(change, true))
        return fail(QStringLiteral("mergeLivePin"), QStringLiteral("invalid live pin"));
    if (!m_database.transaction())
        return fail(QStringLiteral("mergeLivePin"), m_database.lastError().text());
    if (!applyPinProjection(accountId, change)) { m_database.rollback(); return false; }
    QSqlQuery command(m_database); command.prepare(QStringLiteral("DELETE FROM v2_pin_commands WHERE account_id=? AND client_operation_id=?"));
    command.addBindValue(accountId); command.addBindValue(change.clientOperationId);
    if (!command.exec()) {
        m_database.rollback();
        return fail(QStringLiteral("mergeLivePin"), command.lastError().text());
    }
    if (!m_database.commit())
        return fail(QStringLiteral("mergeLivePin"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::stageEdit(
        const QString &accountId, const EditCommand &command) {
    if (!canonicalUuid(accountId) || !validEditCommand(command))
        return fail(QStringLiteral("stageEdit"), QStringLiteral("invalid edit command"));
    if (!m_database.transaction()) return fail(QStringLiteral("stageEdit"), m_database.lastError().text());
    QSqlQuery target(m_database);
    target.prepare(QStringLiteral("SELECT sender_account_id,content_revision,recalled FROM v2_messages "
        "WHERE account_id=? AND conversation_id=? AND message_id=? AND delivery_state='accepted'"));
    target.addBindValue(accountId); target.addBindValue(command.conversationId); target.addBindValue(command.messageId);
    if (!target.exec() || !target.next() || target.value(0).toString()!=accountId
            || target.value(1).toInt()!=command.expectedRevision || target.value(2).toBool()) {
        m_database.rollback(); return fail(QStringLiteral("stageEdit"), QStringLiteral("edit target unavailable"));
    }
    QSqlQuery existing(m_database);
    existing.prepare(QStringLiteral("SELECT conversation_id,message_id,expected_revision,proposed_text "
        "FROM v2_edit_commands WHERE account_id=? AND client_operation_id=?"));
    existing.addBindValue(accountId); existing.addBindValue(command.clientOperationId);
    if (!existing.exec()) { m_database.rollback(); return fail(QStringLiteral("stageEdit"), existing.lastError().text()); }
    if (existing.next()) {
        QList<Mention> storedMentions;
        QSqlQuery mentionQuery(m_database);
        mentionQuery.prepare(QStringLiteral("SELECT target_account_id,start_utf8_byte,"
            "length_utf8_bytes FROM v2_edit_command_mentions WHERE account_id=? "
            "AND client_operation_id=? ORDER BY ordinal"));
        mentionQuery.addBindValue(accountId); mentionQuery.addBindValue(command.clientOperationId);
        if (!mentionQuery.exec()) { m_database.rollback(); return fail(QStringLiteral("stageEdit"), mentionQuery.lastError().text()); }
        while (mentionQuery.next()) storedMentions.append({mentionQuery.value(0).toString(),
            mentionQuery.value(1).toInt(), mentionQuery.value(2).toInt()});
        if (existing.value(0).toString()!=command.conversationId
                || existing.value(1).toString()!=command.messageId
                || existing.value(2).toInt()!=command.expectedRevision
                || existing.value(3).toString()!=command.proposedText
                || !sameMentions(storedMentions, command.mentions)) {
            m_database.rollback(); return fail(QStringLiteral("stageEdit"), QStringLiteral("idempotency conflict"));
        }
        QSqlQuery retry(m_database);
        retry.prepare(QStringLiteral("UPDATE v2_edit_commands SET delivery_state='pending' "
            "WHERE account_id=? AND client_operation_id=? AND delivery_state='failed'"));
        retry.addBindValue(accountId); retry.addBindValue(command.clientOperationId);
        if (!retry.exec()) { m_database.rollback(); return fail(QStringLiteral("stageEdit"), retry.lastError().text()); }
    } else {
        QSqlQuery count(m_database); count.prepare(QStringLiteral("SELECT COUNT(*) FROM v2_edit_commands WHERE account_id=?"));
        count.addBindValue(accountId);
        if (!count.exec() || !count.next() || count.value(0).toInt()>=MaxPendingEditsPerAccount) {
            m_database.rollback(); return fail(QStringLiteral("stageEdit"), QStringLiteral("edit outbox limit reached"));
        }
        QSqlQuery insert(m_database);
        insert.prepare(QStringLiteral("INSERT INTO v2_edit_commands(account_id,conversation_id,message_id,"
            "expected_revision,proposed_text,client_operation_id,delivery_state) VALUES(?,?,?,?,?,?,'pending')"));
        insert.addBindValue(accountId); insert.addBindValue(command.conversationId); insert.addBindValue(command.messageId);
        insert.addBindValue(command.expectedRevision); insert.addBindValue(command.proposedText);
        insert.addBindValue(command.clientOperationId);
        if (!insert.exec()) { m_database.rollback(); return fail(QStringLiteral("stageEdit"), insert.lastError().text()); }
        if (!insertEditMentions(accountId, command.clientOperationId, command.mentions)) {
            m_database.rollback(); return false;
        }
    }
    if (!m_database.commit()) return fail(QStringLiteral("stageEdit"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::markEditFailed(
        const QString &accountId, const QString &operationId, bool conflict) {
    if (!canonicalUuid(accountId) || !validIdentifier(operationId)) return false;
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral("UPDATE v2_edit_commands SET delivery_state=? WHERE account_id=? "
        "AND client_operation_id=? AND delivery_state='pending'"));
    query.addBindValue(conflict ? QStringLiteral("conflict") : QStringLiteral("failed"));
    query.addBindValue(accountId); query.addBindValue(operationId);
    if (!query.exec() || query.numRowsAffected()!=1)
        return fail(QStringLiteral("markEditFailed"), query.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::rebaseEdit(
        const QString &accountId, const QString &staleOperationId,
        const EditCommand &replacement) {
    if (!canonicalUuid(accountId) || !validIdentifier(staleOperationId)
            || !validEditCommand(replacement) || replacement.state!=EditDeliveryState::Pending)
        return false;
    if (!m_database.transaction()) return fail(QStringLiteral("rebaseEdit"), m_database.lastError().text());
    QSqlQuery stale(m_database);
    stale.prepare(QStringLiteral("SELECT conversation_id,message_id,expected_revision,proposed_text FROM "
        "v2_edit_commands WHERE account_id=? AND client_operation_id=? AND delivery_state='conflict'"));
    stale.addBindValue(accountId); stale.addBindValue(staleOperationId);
    if (!stale.exec() || !stale.next() || stale.value(0).toString()!=replacement.conversationId
            || stale.value(1).toString()!=replacement.messageId
            || stale.value(3).toString()!=replacement.proposedText
            || stale.value(2).toInt()>=replacement.expectedRevision) {
        m_database.rollback(); return fail(QStringLiteral("rebaseEdit"), QStringLiteral("stale edit unavailable"));
    }
    QList<Mention> staleMentions;
    QSqlQuery mentionQuery(m_database);
    mentionQuery.prepare(QStringLiteral("SELECT target_account_id,start_utf8_byte,length_utf8_bytes "
        "FROM v2_edit_command_mentions WHERE account_id=? AND client_operation_id=? ORDER BY ordinal"));
    mentionQuery.addBindValue(accountId); mentionQuery.addBindValue(staleOperationId);
    if (!mentionQuery.exec()) { m_database.rollback(); return fail(QStringLiteral("rebaseEdit"), mentionQuery.lastError().text()); }
    while (mentionQuery.next()) staleMentions.append({mentionQuery.value(0).toString(),
        mentionQuery.value(1).toInt(), mentionQuery.value(2).toInt()});
    if (!sameMentions(staleMentions, replacement.mentions)) {
        m_database.rollback(); return fail(QStringLiteral("rebaseEdit"), QStringLiteral("stale edit mention conflict"));
    }
    QSqlQuery revision(m_database);
    revision.prepare(QStringLiteral("SELECT content_revision FROM v2_messages WHERE account_id=? AND "
        "conversation_id=? AND message_id=? AND recalled=0"));
    revision.addBindValue(accountId); revision.addBindValue(replacement.conversationId); revision.addBindValue(replacement.messageId);
    if (!revision.exec() || !revision.next() || revision.value(0).toInt()!=replacement.expectedRevision) {
        m_database.rollback(); return fail(QStringLiteral("rebaseEdit"), QStringLiteral("authoritative revision unavailable"));
    }
    QSqlQuery update(m_database);
    update.prepare(QStringLiteral("UPDATE v2_edit_commands SET expected_revision=?,client_operation_id=?,"
        "delivery_state='pending' WHERE account_id=? AND client_operation_id=?"));
    update.addBindValue(replacement.expectedRevision); update.addBindValue(replacement.clientOperationId);
    update.addBindValue(accountId); update.addBindValue(staleOperationId);
    if (!update.exec() || update.numRowsAffected()!=1) { m_database.rollback(); return fail(QStringLiteral("rebaseEdit"), update.lastError().text()); }
    if (!m_database.commit()) return fail(QStringLiteral("rebaseEdit"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::discardEdit(const QString &accountId, const QString &operationId) {
    if (!canonicalUuid(accountId) || !validIdentifier(operationId)) return false;
    QSqlQuery query(m_database); query.prepare(QStringLiteral(
        "DELETE FROM v2_edit_commands WHERE account_id=? AND client_operation_id=?"));
    query.addBindValue(accountId); query.addBindValue(operationId);
    if (!query.exec() || query.numRowsAffected()!=1) return fail(QStringLiteral("discardEdit"), query.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::applyEdit(const QString &accountId, const EditChange &change) {
    if (!canonicalUuid(accountId) || !validEditChange(change, false)) return false;
    if (!m_database.transaction()) return fail(QStringLiteral("applyEdit"), m_database.lastError().text());
    if (!applyEditProjection(accountId, change)) { m_database.rollback(); return false; }
    QSqlQuery command(m_database); command.prepare(QStringLiteral(
        "DELETE FROM v2_edit_commands WHERE account_id=? AND client_operation_id=?"));
    command.addBindValue(accountId); command.addBindValue(change.clientOperationId);
    if (!command.exec()) { m_database.rollback(); return fail(QStringLiteral("applyEdit"), command.lastError().text()); }
    if (!m_database.commit()) return fail(QStringLiteral("applyEdit"), m_database.lastError().text());
    m_lastError.clear(); return true;
}

bool V2LocalMessageRepository::mergeLiveEdit(const QString &accountId, const EditChange &change) {
    return applyEdit(accountId, change);
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
        "reply_target_message_id, reply_target_sequence, reply_target_sender_account_id, recalled, "
        "content_revision, edited_at, forwarded, forward_source_conversation_id, "
        "forward_source_message_id, forward_source_revision "
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
        message.contentRevision = query.value(13).toInt();
        message.editedAtEpochMs = query.value(14).toLongLong();
        message.forwarded = query.value(15).toBool();
        message.forwardSourceConversationId = query.value(16).toString();
        message.forwardSourceMessageId = query.value(17).toString();
        message.expectedForwardSourceRevision = query.value(18).toInt();
        message.hasReply = !message.reply.targetMessageId.isEmpty();
        result.messages.append(message);
    }
    QSqlQuery mentions(m_database);
    mentions.prepare(QStringLiteral(
        "SELECT client_message_id,target_account_id,start_utf8_byte,length_utf8_bytes "
        "FROM v2_message_mentions WHERE account_id=? AND conversation_id=? "
        "ORDER BY client_message_id,ordinal"));
    mentions.addBindValue(accountId); mentions.addBindValue(conversationId);
    if (!mentions.exec()) { fail(QStringLiteral("loadSnapshot"), mentions.lastError().text()); return {}; }
    while (mentions.next()) {
        auto message = std::find_if(result.messages.begin(), result.messages.end(),
            [&](const Message &value) {
                return value.clientMessageId == mentions.value(0).toString();
            });
        if (message != result.messages.end())
            message->mentions.append({mentions.value(1).toString(), mentions.value(2).toInt(),
                                      mentions.value(3).toInt()});
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
    QSqlQuery pins(m_database);
    pins.prepare(QStringLiteral("SELECT message_id FROM v2_message_pins WHERE account_id=? AND conversation_id=?"));
    pins.addBindValue(accountId); pins.addBindValue(conversationId);
    if (!pins.exec()) { fail(QStringLiteral("loadSnapshot"), pins.lastError().text()); return {}; }
    while (pins.next()) {
        auto message = std::find_if(result.messages.begin(), result.messages.end(),
            [&](const Message &value) { return value.messageId == pins.value(0).toString(); });
        if (message != result.messages.end()) message->pinned = true;
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
    QSqlQuery pinCommands(m_database);
    pinCommands.prepare(QStringLiteral("SELECT message_id,pinned,client_operation_id,delivery_state FROM v2_pin_commands WHERE account_id=? AND conversation_id=? ORDER BY rowid"));
    pinCommands.addBindValue(accountId); pinCommands.addBindValue(conversationId);
    if (!pinCommands.exec()) { fail(QStringLiteral("loadSnapshot"), pinCommands.lastError().text()); return {}; }
    while (pinCommands.next()) {
        PinCommand command{conversationId, pinCommands.value(0).toString(), pinCommands.value(1).toBool(),
            pinCommands.value(2).toString(), pinCommands.value(3).toString()==QStringLiteral("failed") ? DeliveryState::Failed : DeliveryState::Pending};
        if (validPinCommand(command)) result.pinCommands.append(command);
    }
    QSqlQuery editCommands(m_database);
    editCommands.prepare(QStringLiteral("SELECT message_id,expected_revision,proposed_text,"
        "client_operation_id,delivery_state FROM v2_edit_commands WHERE account_id=? "
        "AND conversation_id=? ORDER BY rowid"));
    editCommands.addBindValue(accountId); editCommands.addBindValue(conversationId);
    if (!editCommands.exec()) { fail(QStringLiteral("loadSnapshot"), editCommands.lastError().text()); return {}; }
    while (editCommands.next()) {
        EditCommand command; command.conversationId=conversationId;
        command.messageId=editCommands.value(0).toString(); command.expectedRevision=editCommands.value(1).toInt();
        command.proposedText=editCommands.value(2).toString(); command.clientOperationId=editCommands.value(3).toString();
        const QString state=editCommands.value(4).toString();
        command.state=state==QStringLiteral("failed") ? EditDeliveryState::Failed
            : state==QStringLiteral("conflict") ? EditDeliveryState::Conflict : EditDeliveryState::Pending;
        QSqlQuery mentionQuery(m_database);
        mentionQuery.prepare(QStringLiteral("SELECT target_account_id,start_utf8_byte,"
            "length_utf8_bytes FROM v2_edit_command_mentions WHERE account_id=? "
            "AND client_operation_id=? ORDER BY ordinal"));
        mentionQuery.addBindValue(accountId); mentionQuery.addBindValue(command.clientOperationId);
        if (!mentionQuery.exec()) { fail(QStringLiteral("loadSnapshot"), mentionQuery.lastError().text()); return {}; }
        while (mentionQuery.next()) command.mentions.append({mentionQuery.value(0).toString(),
            mentionQuery.value(1).toInt(), mentionQuery.value(2).toInt()});
        if (validEditCommand(command)) result.editCommands.append(command);
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

QList<V2LocalMessageRepository::PinCommand>
V2LocalMessageRepository::pendingPins(const QString &accountId) {
    QList<PinCommand> result; if (!canonicalUuid(accountId)) return result;
    QSqlQuery query(m_database); query.prepare(QStringLiteral(
        "SELECT conversation_id,message_id,pinned,client_operation_id FROM v2_pin_commands "
        "WHERE account_id=? AND delivery_state='pending' ORDER BY rowid"));
    query.addBindValue(accountId);
    if (!query.exec()) { fail(QStringLiteral("pendingPins"), query.lastError().text()); return {}; }
    while (query.next()) {
        PinCommand command{query.value(0).toString(), query.value(1).toString(),
            query.value(2).toBool(), query.value(3).toString(), DeliveryState::Pending};
        if (validPinCommand(command)) result.append(command);
    }
    m_lastError.clear(); return result;
}

QList<V2LocalMessageRepository::EditCommand>
V2LocalMessageRepository::pendingEdits(const QString &accountId) {
    QList<EditCommand> result; if (!canonicalUuid(accountId)) return result;
    QSqlQuery query(m_database); query.prepare(QStringLiteral("SELECT conversation_id,message_id,"
        "expected_revision,proposed_text,client_operation_id FROM v2_edit_commands WHERE "
        "account_id=? AND delivery_state='pending' ORDER BY rowid"));
    query.addBindValue(accountId);
    if (!query.exec()) { fail(QStringLiteral("pendingEdits"), query.lastError().text()); return {}; }
    while (query.next()) {
        EditCommand command{query.value(0).toString(),query.value(1).toString(),query.value(2).toInt(),
            query.value(3).toString(),query.value(4).toString(),EditDeliveryState::Pending, {}};
        QSqlQuery mentions(m_database);
        mentions.prepare(QStringLiteral(
            "SELECT target_account_id,start_utf8_byte,length_utf8_bytes "
            "FROM v2_edit_command_mentions WHERE account_id=? AND client_operation_id=? "
            "ORDER BY ordinal"));
        mentions.addBindValue(accountId); mentions.addBindValue(command.clientOperationId);
        if (!mentions.exec()) { fail(QStringLiteral("pendingEdits"), mentions.lastError().text()); return {}; }
        while (mentions.next())
            command.mentions.append({mentions.value(0).toString(), mentions.value(1).toInt(),
                                     mentions.value(2).toInt()});
        if (validEditCommand(command)) result.append(command);
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
        "reply_target_sender_account_id, content_revision, edited_at, "
        "forwarded, forward_source_conversation_id, forward_source_message_id, "
        "forward_source_revision) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"));
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
    query.addBindValue(message.contentRevision); query.addBindValue(message.editedAtEpochMs);
    query.addBindValue(message.forwarded);
    query.addBindValue(message.forwardSourceConversationId.isNull()
        ? QStringLiteral("") : message.forwardSourceConversationId);
    query.addBindValue(message.forwardSourceMessageId.isNull()
        ? QStringLiteral("") : message.forwardSourceMessageId);
    query.addBindValue(message.expectedForwardSourceRevision);
    if (!query.exec()) return fail(QStringLiteral("insertMessage"), query.lastError().text());
    return insertMessageMentions(
        accountId, message.conversationId, message.clientMessageId, message.mentions);
}

bool V2LocalMessageRepository::insertMessageMentions(
        const QString &accountId, const QString &conversationId,
        const QString &clientMessageId, const QList<Mention> &mentions) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO v2_message_mentions(account_id,conversation_id,client_message_id,ordinal,"
        "target_account_id,start_utf8_byte,length_utf8_bytes) VALUES(?,?,?,?,?,?,?)"));
    for (qsizetype index = 0; index < mentions.size(); ++index) {
        query.bindValue(0, accountId); query.bindValue(1, conversationId);
        query.bindValue(2, clientMessageId); query.bindValue(3, index);
        query.bindValue(4, mentions[index].targetAccountId);
        query.bindValue(5, mentions[index].startUtf8Byte);
        query.bindValue(6, mentions[index].lengthUtf8Bytes);
        if (!query.exec())
            return fail(QStringLiteral("insertMessageMentions"), query.lastError().text());
    }
    return true;
}

bool V2LocalMessageRepository::insertEditMentions(
        const QString &accountId, const QString &clientOperationId,
        const QList<Mention> &mentions) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral(
        "INSERT INTO v2_edit_command_mentions(account_id,client_operation_id,ordinal,"
        "target_account_id,start_utf8_byte,length_utf8_bytes) VALUES(?,?,?,?,?,?)"));
    for (qsizetype index = 0; index < mentions.size(); ++index) {
        query.bindValue(0, accountId); query.bindValue(1, clientOperationId);
        query.bindValue(2, index); query.bindValue(3, mentions[index].targetAccountId);
        query.bindValue(4, mentions[index].startUtf8Byte);
        query.bindValue(5, mentions[index].lengthUtf8Bytes);
        if (!query.exec())
            return fail(QStringLiteral("insertEditMentions"), query.lastError().text());
    }
    return true;
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
    if (!orphaned.exec()) return fail(QStringLiteral("pruneAccepted"), orphaned.lastError().text());
    QSqlQuery orphanedPins(m_database);
    orphanedPins.prepare(QStringLiteral(
        "DELETE FROM v2_message_pins WHERE account_id=? AND conversation_id=? AND NOT EXISTS "
        "(SELECT 1 FROM v2_messages m WHERE m.account_id=? AND m.conversation_id=? "
        "AND m.message_id=v2_message_pins.message_id)"));
    orphanedPins.addBindValue(accountId); orphanedPins.addBindValue(conversationId);
    orphanedPins.addBindValue(accountId); orphanedPins.addBindValue(conversationId);
    return orphanedPins.exec() || fail(QStringLiteral("pruneAccepted"), orphanedPins.lastError().text());
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

bool V2LocalMessageRepository::applyPinProjection(
        const QString &accountId, const PinChange &change) {
    QSqlQuery query(m_database);
    if (change.pinned) {
        query.prepare(QStringLiteral(
            "INSERT OR IGNORE INTO v2_message_pins(account_id,conversation_id,message_id) "
            "SELECT ?,?,? WHERE EXISTS (SELECT 1 FROM v2_messages WHERE account_id=? "
            "AND conversation_id=? AND message_id=? AND recalled=0)"));
        query.addBindValue(accountId); query.addBindValue(change.conversationId);
        query.addBindValue(change.messageId); query.addBindValue(accountId);
        query.addBindValue(change.conversationId); query.addBindValue(change.messageId);
    } else {
        query.prepare(QStringLiteral(
            "DELETE FROM v2_message_pins WHERE account_id=? AND conversation_id=? AND message_id=?"));
        query.addBindValue(accountId); query.addBindValue(change.conversationId);
        query.addBindValue(change.messageId);
    }
    return query.exec() || fail(QStringLiteral("applyPinProjection"), query.lastError().text());
}

bool V2LocalMessageRepository::applyEditProjection(
        const QString &accountId, const EditChange &change) {
    QSqlQuery query(m_database);
    query.prepare(QStringLiteral("UPDATE v2_messages SET text_content=?,content_revision=?,edited_at=? "
        "WHERE account_id=? AND conversation_id=? AND message_id=? AND delivery_state='accepted' "
        "AND recalled=0 AND content_revision<=?"));
    query.addBindValue(change.text); query.addBindValue(change.contentRevision);
    query.addBindValue(change.occurredAtEpochMs); query.addBindValue(accountId);
    query.addBindValue(change.conversationId); query.addBindValue(change.messageId);
    query.addBindValue(change.contentRevision);
    if (!query.exec()) return fail(QStringLiteral("applyEditProjection"), query.lastError().text());
    if (query.numRowsAffected() == 0) return true;
    QSqlQuery identity(m_database);
    identity.prepare(QStringLiteral("SELECT client_message_id FROM v2_messages WHERE account_id=? "
        "AND conversation_id=? AND message_id=? AND delivery_state='accepted' AND recalled=0"));
    identity.addBindValue(accountId); identity.addBindValue(change.conversationId);
    identity.addBindValue(change.messageId);
    if (!identity.exec()) return fail(QStringLiteral("applyEditProjection"), identity.lastError().text());
    if (!identity.next()) return true;
    const QString clientMessageId = identity.value(0).toString();
    QSqlQuery remove(m_database);
    remove.prepare(QStringLiteral("DELETE FROM v2_message_mentions WHERE account_id=? "
        "AND conversation_id=? AND client_message_id=?"));
    remove.addBindValue(accountId); remove.addBindValue(change.conversationId);
    remove.addBindValue(clientMessageId);
    if (!remove.exec()) return fail(QStringLiteral("applyEditProjection"), remove.lastError().text());
    return insertMessageMentions(
        accountId, change.conversationId, clientMessageId, change.mentions);
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
            || message.createdAtEpochMs <= 0 || message.contentRevision < 0
            || message.contentRevision > MaxContentRevisions
            || ((message.contentRevision == 0) != (message.editedAtEpochMs == 0))
            || !validMentions(message.text, message.mentions)) return false;
    const bool hasForwardSource = !message.forwardSourceConversationId.isEmpty()
        || !message.forwardSourceMessageId.isEmpty();
    if (hasForwardSource && (!message.forwarded || message.hasReply || !message.mentions.isEmpty()
            || !canonicalUuid(message.forwardSourceConversationId)
            || !canonicalUuid(message.forwardSourceMessageId)
            || message.expectedForwardSourceRevision < 0
            || message.expectedForwardSourceRevision > MaxContentRevisions)) return false;
    if (!hasForwardSource && message.expectedForwardSourceRevision != 0) return false;
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
        && message.forwardSourceConversationId.isEmpty()
        && message.forwardSourceMessageId.isEmpty()
        && message.expectedForwardSourceRevision == 0
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
bool V2LocalMessageRepository::validPinChange(
        const PinChange &change, bool sequenceRequired) {
    return canonicalUuid(change.conversationId) && canonicalUuid(change.messageId)
        && canonicalUuid(change.actorAccountId) && validIdentifier(change.clientOperationId)
        && change.occurredAtEpochMs > 0 && (sequenceRequired
            ? change.conversationSequence > 0 : change.conversationSequence >= 0);
}
bool V2LocalMessageRepository::validPinCommand(const PinCommand &command) {
    return canonicalUuid(command.conversationId) && canonicalUuid(command.messageId)
        && validIdentifier(command.clientOperationId)
        && command.state != DeliveryState::Accepted;
}
bool V2LocalMessageRepository::validEditChange(
        const EditChange &change, bool sequenceRequired) {
    QStringEncoder encoder(QStringEncoder::Utf8); const QByteArray text=encoder(change.text);
    return canonicalUuid(change.conversationId) && canonicalUuid(change.messageId)
        && change.contentRevision>=1 && change.contentRevision<=MaxContentRevisions
        && !encoder.hasError() && !text.isEmpty() && text.size()<=MaxTextBytes
        && validMentions(change.text, change.mentions)
        && canonicalUuid(change.actorAccountId) && validIdentifier(change.clientOperationId)
        && change.occurredAtEpochMs>0 && (sequenceRequired
            ? change.conversationSequence>0 : change.conversationSequence>=0);
}
bool V2LocalMessageRepository::validEditCommand(const EditCommand &command) {
    QStringEncoder encoder(QStringEncoder::Utf8); const QByteArray text=encoder(command.proposedText);
    return canonicalUuid(command.conversationId) && canonicalUuid(command.messageId)
        && command.expectedRevision>=0 && command.expectedRevision<=MaxContentRevisions
        && !encoder.hasError() && !text.isEmpty() && text.size()<=MaxTextBytes
        && validIdentifier(command.clientOperationId)
        && validMentions(command.proposedText, command.mentions);
}
bool V2LocalMessageRepository::validMentions(
        const QString &text, const QList<Mention> &mentions) {
    const QByteArray bytes = text.toUtf8();
    if (mentions.size() > 20) return false;
    int previousEnd = 0;
    QSet<QString> targets;
    for (const auto &mention : mentions) {
        if (!canonicalUuid(mention.targetAccountId) || mention.lengthUtf8Bytes <= 0
                || mention.startUtf8Byte < previousEnd || mention.startUtf8Byte >= bytes.size()
                || mention.lengthUtf8Bytes > bytes.size() - mention.startUtf8Byte)
            return false;
        const int end = mention.startUtf8Byte + mention.lengthUtf8Bytes;
        const auto boundary = [&](int index) {
            return index == 0 || index == bytes.size()
                || (static_cast<unsigned char>(bytes.at(index)) & 0xc0U) != 0x80U;
        };
        if (!boundary(mention.startUtf8Byte) || !boundary(end)
                || bytes.at(mention.startUtf8Byte) != '@') return false;
        targets.insert(mention.targetAccountId);
        if (targets.size() > 10) return false;
        previousEnd = end;
    }
    return true;
}
bool V2LocalMessageRepository::sameMentions(
        const QList<Mention> &left, const QList<Mention> &right) {
    if (left.size() != right.size()) return false;
    for (qsizetype index = 0; index < left.size(); ++index)
        if (left[index].targetAccountId != right[index].targetAccountId
                || left[index].startUtf8Byte != right[index].startUtf8Byte
                || left[index].lengthUtf8Bytes != right[index].lengthUtf8Bytes)
            return false;
    return true;
}
bool V2LocalMessageRepository::fail(const QString &operation, const QString &detail) {
    m_lastError = operation + QStringLiteral(": ") + detail;
    qWarning().noquote() << QStringLiteral("[V2LocalStore] operation=%1 outcome=failure").arg(operation);
    return false;
}
