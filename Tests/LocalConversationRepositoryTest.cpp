#include "LocalConversationRepository.h"

#include <QCoreApplication>
#include <QDir>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QTemporaryDir>
#include <QDebug>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[LocalConversationRepositoryTest]" << message;
    return condition;
}

Message makeMessage(int id, qint64 sequence, qint64 timestamp) {
    Message message = Message::createTextMessage(7, QStringLiteral("alice"),
                                                 QStringLiteral("message-%1").arg(id));
    message.setId(id);
    message.setSequence(sequence);
    message.setTimestamp(timestamp);
    message.setSenderName(QStringLiteral("Alice"));
    message.setClientMessageId(QStringLiteral("client-%1").arg(id));
    message.setThumbnail(QStringLiteral("base64-thumbnail-bytes"));
    return message;
}

QSet<QString> tableColumns(const QString &path, const QString &table) {
    const QString connection = QStringLiteral("local-store-schema-inspection");
    QSet<QString> columns;
    {
        QSqlDatabase database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(path);
        if (database.open()) {
            QSqlQuery query(database);
            if (query.exec(QStringLiteral("PRAGMA table_info(%1)").arg(table))) {
                while (query.next()) columns.insert(query.value(1).toString());
            }
        }
        database.close();
    }
    QSqlDatabase::removeDatabase(connection);
    return columns;
}

int databaseUserVersion(const QString &path) {
    const QString connection = QStringLiteral("local-store-version-inspection");
    int version = -1;
    {
        QSqlDatabase database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(path);
        if (database.open()) {
            QSqlQuery query(database);
            if (query.exec(QStringLiteral("PRAGMA user_version")) && query.next())
                version = query.value(0).toInt();
        }
        database.close();
    }
    QSqlDatabase::removeDatabase(connection);
    return version;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    if (!check(directory.isValid(), QStringLiteral("temporary directory unavailable"))) return 1;
    const QString path = directory.filePath(QStringLiteral("client.sqlite"));
    QList<Message> messages;
    for (int i = 1; i <= 520; ++i) messages.append(makeMessage(i, i, 1000 + i));

    {
        LocalConversationRepository repository(path);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"),
                  messages, 520), repository.lastError())) return 1;
        if (!check(repository.saveDraft(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"),
                  QString(10020, QLatin1Char('x'))), repository.lastError())) return 1;
        if (!check(repository.replaceMessages(QStringLiteral("bob"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"),
                  {makeMessage(900, 1, 9000)}, 1), repository.lastError())) return 1;
    }

    {
        LocalConversationRepository repository(path);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        const auto snapshot = repository.loadSnapshot(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Room,
            QStringLiteral("7"));
        if (!check(snapshot.messages.size() == 500, QStringLiteral("message bound not enforced")) ||
            !check(snapshot.messages.first().id() == 21, QStringLiteral("wrong bounded first message")) ||
            !check(snapshot.messages.last().id() == 520, QStringLiteral("wrong bounded last message")) ||
            !check(snapshot.messages.last().senderName() == QStringLiteral("Alice"),
                   QStringLiteral("sender name not restored")) ||
            !check(snapshot.messages.last().thumbnail().isEmpty(),
                   QStringLiteral("thumbnail bytes leaked into metadata cache")) ||
            !check(snapshot.cursor == 520, QStringLiteral("cursor not restored")) ||
            !check(snapshot.draft.size() == LocalConversationRepository::MaxDraftLength,
                   QStringLiteral("draft bound not enforced"))) return 1;

        const auto isolated = repository.loadSnapshot(
            QStringLiteral("bob"), LocalConversationRepository::Kind::Room,
            QStringLiteral("7"));
        if (!check(isolated.messages.size() == 1 && isolated.messages.first().id() == 900,
                   QStringLiteral("account isolation failed"))) return 1;

        Message legacyWithoutClientId = Message::createTextMessage(
            7, QStringLiteral("legacy"), QStringLiteral("no client id"));
        legacyWithoutClientId.setId(899);
        if (!check(repository.upsertMessage(
                  QStringLiteral("legacy-user"), LocalConversationRepository::Kind::Room,
                  QStringLiteral("7"), legacyWithoutClientId, 2),
                  repository.lastError())) return 1;

        Message recalled = snapshot.messages.last();
        recalled.setRecalled(true);
        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"),
                  {recalled}, 521), repository.lastError())) return 1;
        const auto replaced = repository.loadSnapshot(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Room,
            QStringLiteral("7"));
        if (!check(replaced.messages.size() == 1 && replaced.messages.first().recalled(),
                   QStringLiteral("authoritative replacement failed")) ||
            !check(replaced.cursor == 521, QStringLiteral("cursor did not advance")) ||
            !check(replaced.draft.size() == LocalConversationRepository::MaxDraftLength,
                   QStringLiteral("message replacement lost draft"))) return 1;
        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"),
                  {recalled}, 3), repository.lastError()) ||
            !check(repository.loadSnapshot(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"))
                  .cursor == 521, QStringLiteral("cursor regressed"))) return 1;

        if (!check(repository.pruneConversations(
                  QStringLiteral("alice"), LocalConversationRepository::Kind::Room, {}),
                  repository.lastError())) return 1;
        if (!check(repository.loadSnapshot(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"))
                  .messages.isEmpty(), QStringLiteral("prune failed"))) return 1;
        if (!check(repository.loadSnapshot(QStringLiteral("bob"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("7"))
                  .messages.size() == 1, QStringLiteral("prune crossed account boundary"))) return 1;

        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct, QStringLiteral("carol"),
                  {makeMessage(901, 8, 9100)}, 8), repository.lastError()) ||
            !check(repository.saveDraft(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct, QStringLiteral("carol"),
                  QStringLiteral("unloaded draft")), repository.lastError())) return 1;

        Message pendingMessage = makeMessage(0, 0, 9200);
        pendingMessage.setDeliveryState(Message::Sending);
        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct, QStringLiteral("42"),
                  {pendingMessage}, 0), repository.lastError())) return 1;
        const auto pending = repository.pendingSends(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Direct);
        if (!check(pending.size() == 1
                   && pending.first().conversationKey == QStringLiteral("42")
                   && pending.first().message.clientMessageId() == QStringLiteral("client-0"),
                   QStringLiteral("pending send query failed"))) return 1;
        Message acceptedPending = pendingMessage;
        acceptedPending.setId(902);
        acceptedPending.setSequence(9);
        acceptedPending.setDeliveryState(Message::Read);
        if (!check(repository.upsertMessage(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct, QStringLiteral("42"),
                  acceptedPending, 9), repository.lastError())) return 1;
        const auto acceptedSnapshot = repository.loadSnapshot(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Direct,
            QStringLiteral("42"));
        if (!check(acceptedSnapshot.messages.size() == 1
                   && acceptedSnapshot.messages.first().id() == 902
                   && acceptedSnapshot.messages.first().deliveryState() == Message::Read,
                   QStringLiteral("upsert did not replace optimistic identity")) ||
            !check(repository.pendingSends(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct).isEmpty(),
                  QStringLiteral("accepted upsert remained pending"))) return 1;
        pendingMessage.setDeliveryState(Message::Failed);
        if (!check(repository.replaceMessages(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct, QStringLiteral("42"),
                  {pendingMessage}, 0), repository.lastError()) ||
            !check(repository.pendingSends(QStringLiteral("alice"),
                  LocalConversationRepository::Kind::Direct).isEmpty(),
                  QStringLiteral("failed send was treated as automatic retry"))) return 1;

        Message acceptedForClear = makeMessage(903, 10, 9300);
        Message pendingForClear = makeMessage(0, 0, 9400);
        pendingForClear.setClientMessageId(QStringLiteral("clear-pending"));
        pendingForClear.setDeliveryState(Message::Sending);
        if (!check(repository.replaceMessages(QStringLiteral("clear-user"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("88"),
                  {acceptedForClear, pendingForClear}, 10), repository.lastError())
            || !check(repository.saveDraft(QStringLiteral("clear-user"),
                  LocalConversationRepository::Kind::Room, QStringLiteral("88"),
                  QStringLiteral("preserved draft")), repository.lastError())
            || !check(repository.clearCachedMessages(QStringLiteral("clear-user")),
                      repository.lastError())) return 1;
        const auto cleared = repository.loadSnapshot(
            QStringLiteral("clear-user"), LocalConversationRepository::Kind::Room,
            QStringLiteral("88"));
        if (!check(cleared.messages.size() == 1
                       && cleared.messages.first().clientMessageId()
                           == QStringLiteral("clear-pending"),
                   QStringLiteral("cache clear did not preserve pending intent"))
            || !check(cleared.cursor == 0,
                      QStringLiteral("cache clear did not reset sync cursor"))
            || !check(cleared.draft == QStringLiteral("preserved draft"),
                      QStringLiteral("cache clear removed draft"))
            || !check(repository.loadSnapshot(
                          QStringLiteral("bob"),
                          LocalConversationRepository::Kind::Room,
                          QStringLiteral("7")).messages.size() == 1,
                      QStringLiteral("cache clear crossed account boundary"))) return 1;

        LocalConversationRepository::AttachmentCommand attachment;
        attachment.kind = LocalConversationRepository::Kind::Room;
        attachment.conversationKey = QStringLiteral("88");
        attachment.clientMessageId = QStringLiteral("attachment-command-1");
        attachment.sourcePath = directory.filePath(QStringLiteral("source.bin"));
        attachment.fileName = QStringLiteral("source.bin");
        attachment.contentType = QStringLiteral("file");
        attachment.fileSize = 4096;
        attachment.sourceModifiedAtMs = 123456;
        attachment.sourceFingerprint = QString(64, QLatin1Char('a'));
        if (!check(repository.upsertAttachmentCommand(QStringLiteral("clear-user"), attachment),
                   repository.lastError())
            || !check(repository.updateAttachmentCommandState(
                          QStringLiteral("clear-user"), attachment.clientMessageId,
                          LocalConversationRepository::AttachmentState::Uploading, 1024),
                      repository.lastError())) return 1;
        auto attachments = repository.attachmentCommands(
            QStringLiteral("clear-user"), LocalConversationRepository::Kind::Room);
        if (!check(attachments.size() == 1,
                   QStringLiteral("attachment command was not persisted"))
            || !check(attachments.first().state
                          == LocalConversationRepository::AttachmentState::Uploading,
                      QStringLiteral("attachment state was not restored"))
            || !check(attachments.first().transmittedBytes == 1024,
                      QStringLiteral("attachment progress was not restored"))
            || !check(attachments.first().sourceFingerprint == QString(64, QLatin1Char('a')),
                      QStringLiteral("attachment fingerprint was not restored"))) return 1;
        if (!check(repository.clearCachedMessages(QStringLiteral("clear-user")),
                   repository.lastError())
            || !check(repository.attachmentCommands(
                          QStringLiteral("clear-user"),
                          LocalConversationRepository::Kind::Room).size() == 1,
                      QStringLiteral("cache clear removed attachment intent"))) return 1;

        const QSet<QString> attachmentColumns = tableColumns(path, QStringLiteral("attachment_outbox"));
        if (!check(!attachmentColumns.isEmpty(),
                   QStringLiteral("attachment schema inspection failed"))) return 1;
        if (!check(!attachmentColumns.contains(QStringLiteral("upload_token"))
                       && !attachmentColumns.contains(QStringLiteral("upload_id")),
                   QStringLiteral("ephemeral upload authorization leaked into durable schema")))
            return 1;

        const QString copiedPath = directory.filePath(QStringLiteral("copied.sqlite"));
        LocalConversationRepository copied(copiedPath);
        if (!check(copied.initialize(), copied.lastError()) ||
            !check(repository.copyAccountTo(copied, QStringLiteral("alice"),
                                            QStringLiteral("alice-renamed")),
                   repository.lastError())) return 1;
        const auto copiedDirect = copied.loadSnapshot(
            QStringLiteral("alice-renamed"), LocalConversationRepository::Kind::Direct,
            QStringLiteral("carol"));
        if (!check(copiedDirect.messages.size() == 1 && copiedDirect.messages.first().id() == 901,
                   QStringLiteral("account copy lost unloaded messages")) ||
            !check(copiedDirect.messages.first().sender() == QStringLiteral("alice-renamed"),
                   QStringLiteral("account copy did not remap sender identity")) ||
            !check(copiedDirect.cursor == 8,
                   QStringLiteral("account copy lost cursor")) ||
            !check(copiedDirect.draft == QStringLiteral("unloaded draft"),
                   QStringLiteral("account copy lost draft"))) return 1;

        const auto copiedAttachments = copied.attachmentCommands(
            QStringLiteral("alice-renamed"), LocalConversationRepository::Kind::Room);
        if (!check(copiedAttachments.isEmpty(),
                   QStringLiteral("account copy crossed source account boundary"))) return 1;

        attachment.kind = LocalConversationRepository::Kind::Direct;
        attachment.conversationKey = QStringLiteral("carol");
        attachment.clientMessageId = QStringLiteral("rename-attachment");
        if (!check(repository.upsertAttachmentCommand(QStringLiteral("alice"), attachment),
                   repository.lastError())) return 1;
        const QString copiedAgainPath = directory.filePath(QStringLiteral("copied-again.sqlite"));
        LocalConversationRepository copiedAgain(copiedAgainPath);
        if (!check(copiedAgain.initialize(), copiedAgain.lastError())
            || !check(repository.copyAccountTo(copiedAgain, QStringLiteral("alice"),
                                               QStringLiteral("alice-renamed")),
                      repository.lastError())) return 1;
        const auto renamedAttachments = copiedAgain.attachmentCommands(
            QStringLiteral("alice-renamed"), LocalConversationRepository::Kind::Direct);
        if (!check(renamedAttachments.size() == 1
                       && renamedAttachments.first().clientMessageId
                           == QStringLiteral("rename-attachment"),
                   QStringLiteral("account copy lost attachment intent"))) return 1;
        if (!check(repository.removeConversation(
                       QStringLiteral("alice"), LocalConversationRepository::Kind::Direct,
                       QStringLiteral("carol")), repository.lastError())
            || !check(repository.attachmentCommands(
                          QStringLiteral("alice"),
                          LocalConversationRepository::Kind::Direct).isEmpty(),
                      QStringLiteral("conversation eviction retained attachment intent"))) return 1;
    }

    const QString versionOnePath = directory.filePath(QStringLiteral("version-one.sqlite"));
    {
        const QString connection = QStringLiteral("version-one-schema-probe");
        QSqlDatabase database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(versionOnePath);
        if (!check(database.open(), QStringLiteral("version one schema probe open failed"))) return 1;
        QSqlQuery query(database);
        if (!check(query.exec(QStringLiteral(
                "CREATE TABLE conversations (account TEXT NOT NULL, kind TEXT NOT NULL, "
                "conversation_key TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, "
                "draft TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, "
                "PRIMARY KEY(account, kind, conversation_key))")),
                   QStringLiteral("version one conversation setup failed"))
            || !check(query.exec(QStringLiteral("PRAGMA user_version = 1")),
                      QStringLiteral("version one marker setup failed"))) return 1;
        database.close();
        database = QSqlDatabase();
        QSqlDatabase::removeDatabase(connection);
    }
    {
        LocalConversationRepository repository(versionOnePath);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        if (!check(databaseUserVersion(versionOnePath) == 2,
                   QStringLiteral("version one database did not migrate to version two"))) return 1;
    }

    const QString futurePath = directory.filePath(QStringLiteral("future.sqlite"));
    {
        const QString connection = QStringLiteral("future-schema-probe");
        QSqlDatabase database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connection);
        database.setDatabaseName(futurePath);
        if (!check(database.open(), QStringLiteral("future schema probe open failed"))) return 1;
        QSqlQuery query(database);
        if (!check(query.exec(QStringLiteral("PRAGMA user_version = 99")),
                   QStringLiteral("future schema setup failed"))) return 1;
        database.close();
        database = QSqlDatabase();
        QSqlDatabase::removeDatabase(connection);
    }
    {
        LocalConversationRepository repository(futurePath);
        if (!check(!repository.initialize(), QStringLiteral("future schema was accepted")) ||
            !check(repository.lastError().contains(QStringLiteral("newer")),
                   QStringLiteral("future schema error is not diagnostic"))) return 1;
    }

    qInfo() << "[LocalConversationRepositoryTest] PASS";
    return 0;
}
