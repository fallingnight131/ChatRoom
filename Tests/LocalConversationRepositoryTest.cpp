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
    return message;
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
            !check(snapshot.cursor == 520, QStringLiteral("cursor not restored")) ||
            !check(snapshot.draft.size() == LocalConversationRepository::MaxDraftLength,
                   QStringLiteral("draft bound not enforced"))) return 1;

        const auto isolated = repository.loadSnapshot(
            QStringLiteral("bob"), LocalConversationRepository::Kind::Room,
            QStringLiteral("7"));
        if (!check(isolated.messages.size() == 1 && isolated.messages.first().id() == 900,
                   QStringLiteral("account isolation failed"))) return 1;

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
