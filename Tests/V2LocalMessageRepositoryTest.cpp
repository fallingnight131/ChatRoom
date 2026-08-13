#include "V2LocalMessageRepository.h"

#include <QCoreApplication>
#include <QSet>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QTemporaryDir>
#include <QDebug>
#include <algorithm>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}

V2LocalMessageRepository::Message reply(
        const QString &conversationId, const QString &accountId,
        const QString &deviceId) {
    V2LocalMessageRepository::Message value;
    value.conversationId = conversationId;
    value.senderAccountId = accountId;
    value.senderDeviceId = deviceId;
    value.clientMessageId = QStringLiteral("client-reply-1");
    value.text = QStringLiteral("回复内容");
    value.createdAtEpochMs = 1000;
    value.hasReply = true;
    value.reply.targetMessageId = QStringLiteral("40000000-0000-4000-8000-000000000001");
    value.reply.targetConversationSequence = 7;
    value.reply.targetSenderAccountId = QStringLiteral("50000000-0000-4000-8000-000000000001");
    return value;
}

QSet<QString> columns(const QString &path) {
    const QString connection = QStringLiteral("v2-local-schema-probe");
    QSet<QString> result;
    {
        auto database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(path);
        if (database.open()) {
            QSqlQuery query(database);
            if (query.exec(QStringLiteral("PRAGMA table_info(v2_messages)")))
                while (query.next()) result.insert(query.value(1).toString());
        }
        database.close();
    }
    QSqlDatabase::removeDatabase(connection);
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    if (!check(directory.isValid(), QStringLiteral("temporary directory unavailable"))) return 1;
    const QString path = directory.filePath(QStringLiteral("v2-messages.sqlite"));
    const QString alice = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString bob = QStringLiteral("10000000-0000-4000-8000-000000000002");
    const QString device = QStringLiteral("20000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("30000000-0000-4000-8000-000000000001");
    auto pending = reply(conversation, alice, device);

    {
        V2LocalMessageRepository repository(path);
        if (!check(repository.initialize(), repository.lastError())
                || !check(repository.upsertPending(alice, pending), repository.lastError())
                || !check(repository.saveDraft(alice, conversation, QStringLiteral("draft")),
                          repository.lastError())) return 1;
        auto conflict = pending;
        conflict.reply.targetConversationSequence = 6;
        if (!check(!repository.upsertPending(alice, conflict),
                   QStringLiteral("changed reply target was accepted as an idempotent retry"))) return 1;
        auto spoofed = pending;
        spoofed.clientMessageId = QStringLiteral("spoofed-local-sender");
        spoofed.senderAccountId = bob;
        if (!check(!repository.upsertPending(alice, spoofed),
                   QStringLiteral("local pending send accepted a different sender"))) return 1;
        auto bobPending = pending;
        bobPending.senderAccountId = bob;
        if (!check(repository.upsertPending(bob, bobPending), repository.lastError())) return 1;
    }

    {
        V2LocalMessageRepository repository(path);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        const auto restored = repository.loadSnapshot(alice, conversation);
        if (!check(restored.messages.size() == 1,
                   QStringLiteral("pending reply was not restored"))
                || !check(restored.messages.first().hasReply
                        && restored.messages.first().reply.targetConversationSequence == 7,
                    QStringLiteral("reply target identity was not restored"))
                || !check(restored.messages.first().text == QStringLiteral("回复内容"),
                    QStringLiteral("UTF-8 text was not restored"))
                || !check(restored.draft == QStringLiteral("draft"),
                    QStringLiteral("draft was not restored"))
                || !check(repository.pendingSends(alice).size() == 1,
                    QStringLiteral("pending outbox intent was not restored"))) return 1;

        if (!check(repository.markFailed(alice, conversation, pending.clientMessageId),
                   repository.lastError())
                || !check(repository.pendingSends(alice).isEmpty(),
                    QStringLiteral("failed send remained automatic outbox work"))) return 1;
        pending.state = V2LocalMessageRepository::DeliveryState::Pending;
        if (!check(repository.upsertPending(alice, pending), repository.lastError())
                || !check(repository.applyAccepted(
                    alice, conversation, pending.clientMessageId,
                    QStringLiteral("60000000-0000-4000-8000-000000000001"), 8, 1200),
                    repository.lastError())) return 1;
        auto accepted = repository.loadSnapshot(alice, conversation);
        if (!check(accepted.messages.size() == 1
                        && accepted.messages.first().state
                            == V2LocalMessageRepository::DeliveryState::Accepted,
                    QStringLiteral("ACK did not reconcile the optimistic item"))
                || !check(accepted.messages.first().hasReply
                        && accepted.messages.first().reply.targetMessageId
                            == pending.reply.targetMessageId,
                    QStringLiteral("ACK reconciliation lost reply identity"))
                || !check(accepted.cursor == 8,
                    QStringLiteral("ACK did not advance the monotonic cursor"))) return 1;

        V2LocalMessageRepository::ReactionCommand reaction;
        reaction.conversationId = conversation;
        reaction.messageId = accepted.messages.first().messageId;
        reaction.reaction = V2LocalMessageRepository::ReactionKind::Love;
        reaction.active = true;
        reaction.clientOperationId = QStringLiteral("reaction-operation-1");
        if (!check(repository.stageReaction(alice, reaction), repository.lastError())) return 1;
        auto optimisticReaction = repository.loadSnapshot(alice, conversation);
        if (!check(optimisticReaction.reactionCommands.size() == 1
                        && optimisticReaction.messages.first().reactions.size() == 1
                        && optimisticReaction.messages.first().reactions.first()
                            .actorAccountIds.contains(alice),
                   QStringLiteral("optimistic reaction was not stored atomically"))
                || !check(repository.pendingReactions(alice).size() == 1,
                    QStringLiteral("reaction outbox was not restart safe"))) return 1;
        if (!check(repository.markReactionFailed(alice, reaction.clientOperationId),
                   repository.lastError())
                || !check(repository.pendingReactions(alice).isEmpty(),
                    QStringLiteral("failed reaction remained automatic work"))) return 1;
        reaction.state = V2LocalMessageRepository::DeliveryState::Pending;
        if (!check(repository.stageReaction(alice, reaction), repository.lastError())) return 1;
        V2LocalMessageRepository::ReactionChange applied{
            conversation, 9, reaction.messageId, reaction.reaction, true, alice,
            reaction.clientOperationId, 1250};
        if (!check(repository.applyReaction(alice, applied), repository.lastError())) return 1;
        const auto appliedReaction = repository.loadSnapshot(alice, conversation);
        if (!check(appliedReaction.reactionCommands.isEmpty()
                        && appliedReaction.cursor == 9
                        && appliedReaction.messages.first().reactions.first()
                            .actorAccountIds.contains(alice),
                   QStringLiteral("reaction ACK did not converge projection and cursor"))) return 1;

        V2LocalMessageRepository::ReactionChange remoteReaction{
            conversation, 10, reaction.messageId,
            V2LocalMessageRepository::ReactionKind::Like, true, bob,
            QStringLiteral("reaction-operation-remote"), 1300};
        if (!check(repository.mergeServerPage(
                       alice, conversation, {}, 10, {}, {}, {remoteReaction}),
                   repository.lastError())) return 1;
        const auto afterRemoteReaction = repository.loadSnapshot(alice, conversation);
        if (!check(afterRemoteReaction.cursor == 10
                        && afterRemoteReaction.messages.first().reactions.size() == 2,
                   QStringLiteral("history reaction did not update durable aggregate"))
                || !check(repository.loadSnapshot(bob, conversation).messages.first()
                            .reactions.isEmpty(),
                    QStringLiteral("reaction projection crossed account boundary"))) return 1;

        if (!check(repository.upsertPending(alice, pending), repository.lastError())) return 1;
        accepted = repository.loadSnapshot(alice, conversation);
        if (!check(accepted.messages.first().state
                        == V2LocalMessageRepository::DeliveryState::Accepted,
                   QStringLiteral("retry downgraded an accepted message"))) return 1;

        auto authoritative = accepted.messages.first();
        authoritative.reply.targetSenderAccountId =
            QStringLiteral("50000000-0000-4000-8000-000000000002");
        authoritative.acceptedAtEpochMs = 1300;
        if (!check(repository.mergeServerMessage(alice, authoritative, 9),
                   repository.lastError())) return 1;
        const auto merged = repository.loadSnapshot(alice, conversation);
        if (!check(merged.messages.size() == 1
                        && merged.messages.first().reply.targetSenderAccountId
                            == authoritative.reply.targetSenderAccountId,
                    QStringLiteral("authoritative reply projection was not merged"))
                || !check(merged.cursor == 10,
                    QStringLiteral("history merge regressed cursor"))
                || !check(repository.loadSnapshot(bob, conversation).messages.size() == 1,
                    QStringLiteral("history merge crossed account boundary"))) return 1;

        if (!check(repository.mergeServerPage(alice, conversation, {}, 11),
                   repository.lastError())
                || !check(repository.loadSnapshot(alice, conversation).cursor == 11,
                    QStringLiteral("mutation-only page did not advance cursor"))) return 1;
        auto live = authoritative;
        live.messageId = QStringLiteral("60000000-0000-4000-8000-000000000002");
        live.clientMessageId = QStringLiteral("remote-live-1");
        live.conversationSequence = 12;
        live.acceptedAtEpochMs = 1400;
        live.createdAtEpochMs = 1400;
        if (!check(repository.mergeLiveMessage(alice, live), repository.lastError())) return 1;
        const auto afterLive = repository.loadSnapshot(alice, conversation);
        if (!check(afterLive.messages.size() == 2 && afterLive.cursor == 11,
                   QStringLiteral("live message incorrectly advanced history cursor"))) return 1;

        if (!check(repository.mergeServerPage(
                       alice, conversation, {}, 12, {authoritative.messageId}, {}),
                   repository.lastError())) return 1;
        const auto recalled = repository.loadSnapshot(alice, conversation);
        const auto recalledTarget = std::find_if(
            recalled.messages.cbegin(), recalled.messages.cend(),
            [&](const auto &message) { return message.messageId == authoritative.messageId; });
        if (!check(recalledTarget != recalled.messages.cend()
                        && recalledTarget->recalled && recalledTarget->text.isEmpty(),
                   QStringLiteral("recall did not erase cached target content"))) return 1;
        if (!check(repository.mergeServerPage(
                       alice, conversation, {}, 13, {}, {live.messageId}),
                   repository.lastError())) return 1;
        const auto afterDeletion = repository.loadSnapshot(alice, conversation);
        if (!check(std::none_of(afterDeletion.messages.cbegin(), afterDeletion.messages.cend(),
                       [&](const auto &message) { return message.messageId == live.messageId; }),
                   QStringLiteral("administrative deletion retained cached message"))) return 1;

        auto invalidSecond = live;
        invalidSecond.messageId = QStringLiteral("60000000-0000-4000-8000-000000000003");
        invalidSecond.clientMessageId = QStringLiteral("remote-invalid-order");
        invalidSecond.conversationSequence = 10;
        if (!check(!repository.mergeServerPage(
                       alice, conversation, {live, invalidSecond}, 11),
                   QStringLiteral("unordered history page was accepted"))
                || !check(repository.loadSnapshot(alice, conversation).cursor == 13,
                    QStringLiteral("rejected page changed durable cursor"))) return 1;
    }

    const auto schema = columns(path);
    if (!check(schema.contains(QStringLiteral("reply_target_message_id")),
               QStringLiteral("reply identity schema is missing"))
            || !check(!schema.contains(QStringLiteral("reply_body"))
                    && !schema.contains(QStringLiteral("quote_body")),
                QStringLiteral("copied quote content leaked into durable schema"))) return 1;

    const QString futurePath = directory.filePath(QStringLiteral("future.sqlite"));
    {
        const QString connection = QStringLiteral("v2-future-schema-probe");
        auto database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(futurePath);
        if (!check(database.open(), QStringLiteral("future database open failed"))) return 1;
        QSqlQuery query(database);
        if (!check(query.exec(QStringLiteral("PRAGMA user_version = 99")),
                   QStringLiteral("future version setup failed"))) return 1;
        database.close(); database = QSqlDatabase(); QSqlDatabase::removeDatabase(connection);
    }
    V2LocalMessageRepository future(futurePath);
    if (!check(!future.initialize() && future.lastError().contains(QStringLiteral("newer")),
               QStringLiteral("future local schema was accepted"))) return 1;

    qInfo() << "[V2LocalMessageRepositoryTest] PASS";
    return 0;
}
