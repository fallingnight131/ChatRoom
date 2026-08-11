#include "OutgoingMessageService.h"

#include <QCoreApplication>
#include <QDebug>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[OutgoingMessageServiceTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    if (!check(directory.isValid(), QStringLiteral("temporary directory unavailable")))
        return 1;

    LocalConversationRepository repository(
        directory.filePath(QStringLiteral("client.sqlite")));
    if (!check(repository.initialize(), repository.lastError())) return 1;
    OutgoingMessageService service(&repository);

    OutgoingMessageService::StagedSend roomSend;
    const auto room = OutgoingMessageService::roomTarget(7);
    if (!check(service.stage(
            QStringLiteral("alice"), room, QStringLiteral("alice"),
            QStringLiteral("Alice"), QStringLiteral("hello"), Message::Text,
            12, &roomSend), service.lastError())
        || !check(!roomSend.message.clientMessageId().isEmpty(),
                  QStringLiteral("stage did not allocate a client ID"))
        || !check(roomSend.message.deliveryState() == Message::Sending,
                  QStringLiteral("stage did not enter sending state"))
        || !check(roomSend.command.target.roomId == 7
                      && roomSend.command.content == QStringLiteral("hello"),
                  QStringLiteral("room transport command is wrong"))) return 1;

    if (!check(service.recoverRooms(QStringLiteral("alice"), {}).isEmpty(),
               QStringLiteral("unauthorized room was retried"))) return 1;
    const auto roomRetries = service.recoverRooms(
        QStringLiteral("alice"), QSet<int>{7});
    if (!check(roomRetries.size() == 1
                   && roomRetries.first().clientMessageId
                       == roomSend.message.clientMessageId(),
               QStringLiteral("restart retry did not reuse the client ID"))) return 1;

    Message accepted = roomSend.message;
    if (!check(service.recordAccepted(
            QStringLiteral("alice"), room, &accepted, 501, 13, 1700000000000),
            service.lastError())
        || !check(accepted.id() == 501 && accepted.sequence() == 13
                      && accepted.deliveryState() == Message::Accepted,
                  QStringLiteral("authoritative acceptance was not applied"))
        || !check(service.recoverRooms(QStringLiteral("alice"), QSet<int>{7}).isEmpty(),
                  QStringLiteral("accepted message remained retryable"))) return 1;

    OutgoingMessageService::StagedSend directSend;
    const auto direct = OutgoingMessageService::directTarget(
        QStringLiteral("42"), QStringLiteral("old-name"));
    if (!check(service.stage(
            QStringLiteral("alice"), direct, QStringLiteral("alice"),
            QStringLiteral("Alice"), QStringLiteral("你好"), Message::Emoji,
            4, &directSend), service.lastError())) return 1;
    const auto directRetries = service.recoverDirects(
        QStringLiteral("alice"),
        {{QStringLiteral("42"), QStringLiteral("renamed-peer")}});
    if (!check(directRetries.size() == 1
                   && directRetries.first().target.peerUsername
                       == QStringLiteral("renamed-peer")
                   && directRetries.first().clientMessageId
                       == directSend.message.clientMessageId(),
               QStringLiteral("stable friendship key did not resolve current peer")))
        return 1;

    Message failed = directSend.message;
    if (!check(service.recordFailed(
            QStringLiteral("alice"), direct, &failed, 4), service.lastError())
        || !check(failed.deliveryState() == Message::Failed,
                  QStringLiteral("rejection did not enter failed state"))
        || !check(service.recoverDirects(
                      QStringLiteral("alice"),
                      {{QStringLiteral("42"), QStringLiteral("renamed-peer")}})
                      .isEmpty(),
                  QStringLiteral("failed message was retried automatically"))) return 1;

    OutgoingMessageService::Command retry;
    const auto renamedDirect = OutgoingMessageService::directTarget(
        QStringLiteral("42"), QStringLiteral("renamed-peer"));
    if (!check(service.prepareRetry(
            QStringLiteral("alice"), renamedDirect, failed, 4, &retry),
            service.lastError())
        || !check(retry.clientMessageId == failed.clientMessageId(),
                  QStringLiteral("manual retry changed the idempotency key"))
        || !check(service.recoverDirects(
                      QStringLiteral("alice"),
                      {{QStringLiteral("42"), QStringLiteral("renamed-peer")}})
                      .size() == 1,
                  QStringLiteral("manual retry was not made restart-safe"))) return 1;

    OutgoingMessageService onlineOnly;
    OutgoingMessageService::StagedSend transient;
    if (!check(onlineOnly.stage(
            QStringLiteral("alice"), room, QStringLiteral("alice"),
            QStringLiteral("Alice"), QStringLiteral("online"), Message::Text,
            0, &transient), QStringLiteral("online-only fallback cannot send")))
        return 1;

    OutgoingMessageService::StagedSend unsupported;
    if (!check(!service.stage(
            QStringLiteral("alice"), room, QStringLiteral("alice"),
            QStringLiteral("Alice"), QStringLiteral("file"), Message::File,
            13, &unsupported),
            QStringLiteral("attachment entered the text outbox without a command model")))
        return 1;

    qInfo() << "[OutgoingMessageServiceTest] PASS";
    return 0;
}
