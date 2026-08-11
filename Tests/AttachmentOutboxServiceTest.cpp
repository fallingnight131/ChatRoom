#include "AttachmentOutboxService.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QFile>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[AttachmentOutboxServiceTest]" << message;
    return condition;
}

bool writeSource(const QString &path, const QByteArray &bytes) {
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    if (!check(directory.isValid(), QStringLiteral("temporary directory unavailable")))
        return 1;

    const QString databasePath = directory.filePath(QStringLiteral("client.sqlite"));
    const QString sourcePath = directory.filePath(QStringLiteral("report.bin"));
    if (!check(writeSource(sourcePath, QByteArray(150000, 'a')),
               QStringLiteral("cannot create attachment source"))) return 1;

    QString roomClientId;
    {
        LocalConversationRepository repository(databasePath);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        AttachmentOutboxService service(&repository);
        AttachmentOutboxService::Command command;
        if (!check(service.stage(
                QStringLiteral("alice"), AttachmentOutboxService::roomTarget(7),
                sourcePath, QStringLiteral("file"), &command), service.lastError())
            || !check(!command.clientMessageId.isEmpty() && command.fileSize == 150000,
                      QStringLiteral("room attachment was not staged"))) return 1;
        roomClientId = command.clientMessageId;
        if (!check(service.recordUploading(QStringLiteral("alice"), roomClientId),
                   service.lastError())
            || !check(service.recordProgress(QStringLiteral("alice"), roomClientId, 90000),
                      service.lastError())
            || !check(service.recordFinalizing(QStringLiteral("alice"), roomClientId, 150000),
                      service.lastError())) return 1;
    }

    {
        LocalConversationRepository repository(databasePath);
        if (!check(repository.initialize(), repository.lastError())) return 1;
        AttachmentOutboxService service(&repository);
        if (!check(service.recoverRooms(QStringLiteral("alice"), {}).isEmpty(),
                   QStringLiteral("unauthorized room attachment recovered"))) return 1;
        const auto recovered = service.recoverRooms(
            QStringLiteral("alice"), QSet<int>{7});
        if (!check(recovered.size() == 1
                       && recovered.first().clientMessageId == roomClientId,
                   QStringLiteral("restart did not preserve attachment idempotency key")))
            return 1;
        const auto stored = repository.attachmentCommands(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Room);
        if (!check(stored.size() == 1
                       && stored.first().state
                           == LocalConversationRepository::AttachmentState::PendingAuthorization
                       && stored.first().transmittedBytes == 0,
                   QStringLiteral("restart did not discard ephemeral upload progress")))
            return 1;

        if (!check(writeSource(sourcePath, QByteArray(150000, 'b')),
                   QStringLiteral("cannot replace attachment source"))) return 1;
        QFile source(sourcePath);
        if (!check(source.open(QIODevice::ReadOnly)
                       && source.setFileTime(
                           QDateTime::fromMSecsSinceEpoch(stored.first().sourceModifiedAtMs),
                           QFileDevice::FileModificationTime),
                   QStringLiteral("cannot preserve source timestamp for fingerprint test")))
            return 1;
        source.close();
        if (!check(service.recoverRooms(QStringLiteral("alice"), QSet<int>{7}).isEmpty(),
                   QStringLiteral("changed source was automatically transmitted"))) return 1;
        const auto changed = repository.attachmentCommands(
            QStringLiteral("alice"), LocalConversationRepository::Kind::Room);
        if (!check(changed.size() == 1
                       && changed.first().state
                           == LocalConversationRepository::AttachmentState::Failed
                       && changed.first().failureCode == QStringLiteral("SOURCE_CHANGED"),
                   QStringLiteral("changed source did not enter stable failed state")))
            return 1;
        AttachmentOutboxService::Command unsafeRetry;
        if (!check(!service.prepareRetry(
                       QStringLiteral("alice"), AttachmentOutboxService::roomTarget(7),
                       roomClientId, &unsafeRetry),
                   QStringLiteral("manual retry bypassed source revision validation")))
            return 1;
        const QString replacementPath = directory.filePath(QStringLiteral("replacement.bin"));
        if (!check(writeSource(replacementPath, QByteArray(4096, 'c')),
                   QStringLiteral("cannot create replacement source"))
            || !check(service.replaceSource(
                          QStringLiteral("alice"), roomClientId, replacementPath),
                      service.lastError())) return 1;
        AttachmentOutboxService::Command safeRetry;
        if (!check(service.prepareRetry(
                       QStringLiteral("alice"), AttachmentOutboxService::roomTarget(7),
                       roomClientId, &safeRetry), service.lastError())
            || !check(safeRetry.clientMessageId == roomClientId
                          && safeRetry.sourcePath == replacementPath
                          && safeRetry.fileSize == 4096,
                      QStringLiteral("source replacement changed command identity"))) return 1;
        if (!check(service.cancel(QStringLiteral("alice"), roomClientId),
                   service.lastError())
            || !check(repository.attachmentCommands(
                          QStringLiteral("alice"),
                          LocalConversationRepository::Kind::Room).isEmpty(),
                      QStringLiteral("cancel did not remove durable command"))) return 1;

        const QString directSource = directory.filePath(QStringLiteral("photo.jpg"));
        if (!check(writeSource(directSource, QByteArray("jpeg-data")),
                   QStringLiteral("cannot create direct source"))) return 1;
        AttachmentOutboxService::Command direct;
        if (!check(service.stage(
                QStringLiteral("alice"), AttachmentOutboxService::directTarget(
                    QStringLiteral("42"), QStringLiteral("old-name")),
                directSource, QStringLiteral("image"), &direct), service.lastError()))
            return 1;
        const auto directRecovered = service.recoverDirects(
            QStringLiteral("alice"),
            {{QStringLiteral("42"), QStringLiteral("renamed-peer")}});
        if (!check(directRecovered.size() == 1
                       && directRecovered.first().target.peerUsername
                           == QStringLiteral("renamed-peer"),
                   QStringLiteral("direct recovery did not resolve current peer"))) return 1;
        if (!check(service.complete(QStringLiteral("alice"), direct.clientMessageId),
                   service.lastError())
            || !check(repository.attachmentCommands(
                          QStringLiteral("alice"),
                          LocalConversationRepository::Kind::Direct).isEmpty(),
                      QStringLiteral("completion did not remove durable command"))) return 1;
    }

    AttachmentOutboxService unavailable;
    AttachmentOutboxService::Command rejected;
    if (!check(!unavailable.stage(
            QStringLiteral("alice"), AttachmentOutboxService::roomTarget(7),
            sourcePath, QStringLiteral("file"), &rejected),
        QStringLiteral("attachment intent was accepted without durable storage"))) return 1;

    qInfo() << "[AttachmentOutboxServiceTest] PASS";
    return 0;
}
