#include "ConversationSyncService.h"

#include <QCoreApplication>
#include <QDebug>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[ConversationSyncServiceTest]" << message;
    return condition;
}

Message accepted(int id, qint64 sequence) {
    Message message = Message::createTextMessage(
        7, QStringLiteral("alice"), QStringLiteral("accepted"));
    message.setId(id);
    message.setSequence(sequence);
    return message;
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
    const ConversationSyncService::ConversationRef room{
        LocalConversationRepository::Kind::Room, QStringLiteral("7")};
    if (!check(repository.replaceMessages(
            QStringLiteral("alice"), room.kind, room.key,
            {accepted(100, 8)}, 8), repository.lastError())
        || !check(repository.saveDraft(
            QStringLiteral("alice"), room.kind, room.key,
            QStringLiteral("draft")), repository.lastError())) return 1;

    ConversationSyncService service(&repository, QStringLiteral("alice"));
    const auto hydrated = service.hydrate(room);
    if (!check(hydrated.messages.size() == 1 && service.cursor(room) == 8,
               QStringLiteral("hydrate did not restore snapshot cursor"))) return 1;
    service.advance(room, 7);
    service.advance(room, 11);
    if (!check(service.cursor(room) == 11,
               QStringLiteral("cursor is not monotonic"))) return 1;
    const auto continued = service.applyPage(
        room, true, {12}, 12, true);
    if (!check(continued.cursor == 12 && continued.requestNext,
               QStringLiteral("advancing page did not schedule continuation")))
        return 1;
    const auto stalled = service.applyPage(
        room, true, {}, 12, true);
    if (!check(!stalled.requestNext
                   && service.lastError().contains(QStringLiteral("did not advance")),
               QStringLiteral("stalled continuation was not stopped"))) return 1;
    const auto legacy = service.applyPage(
        room, false, {4, 13, 8}, 0, false);
    if (!check(legacy.cursor == 13 && !legacy.requestNext,
               QStringLiteral("legacy page high watermark is wrong"))) return 1;

    Message live = accepted(101, 13);
    if (!check(service.upsert(room, live), service.lastError())) return 1;
    ConversationSyncService restarted(&repository, QStringLiteral("alice"));
    if (!check(restarted.hydrate(room).cursor == 13,
               QStringLiteral("upsert did not persist the cursor"))) return 1;

    const ConversationSyncService::ConversationRef provisional{
        LocalConversationRepository::Kind::Direct, QStringLiteral("peer:bob")};
    const ConversationSyncService::ConversationRef stable{
        LocalConversationRepository::Kind::Direct, QStringLiteral("42")};
    service.advance(provisional, 15);
    service.moveCursor(provisional, stable);
    if (!check(service.cursor(provisional) == 0 && service.cursor(stable) == 15,
               QStringLiteral("provisional cursor promotion failed"))) return 1;

    Message pending = Message::createTextMessage(
        7, QStringLiteral("alice"), QStringLiteral("pending"));
    pending.setClientMessageId(QStringLiteral("pending-id"));
    pending.setDeliveryState(Message::Sending);
    if (!check(service.upsert(room, pending), service.lastError())
        || !check(service.clearCachedMessages(), service.lastError())) return 1;
    const auto cleared = service.hydrate(room);
    if (!check(service.cursor(room) == 0 && cleared.cursor == 0,
               QStringLiteral("cache clear retained a cursor"))
        || !check(cleared.messages.size() == 1
                      && cleared.messages.first().clientMessageId()
                          == QStringLiteral("pending-id"),
                  QStringLiteral("cache clear lost unresolved intent"))
        || !check(cleared.draft == QStringLiteral("draft"),
                  QStringLiteral("cache clear lost draft"))) return 1;

    ConversationSyncService onlineOnly(nullptr, QStringLiteral("alice"));
    onlineOnly.advance(room, 3);
    if (!check(onlineOnly.upsert(room, accepted(102, 3)),
               QStringLiteral("online-only persistence fallback failed"))
        || !check(onlineOnly.clearCachedMessages(),
                  QStringLiteral("online-only cache reset failed"))
        || !check(onlineOnly.cursor(room) == 0,
                  QStringLiteral("online-only cursor reset failed"))) return 1;

    qInfo() << "[ConversationSyncServiceTest] PASS";
    return 0;
}
