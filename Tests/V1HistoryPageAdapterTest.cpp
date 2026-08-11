#include "V1HistoryPageAdapter.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[V1HistoryPageAdapterTest]" << message;
    return condition;
}

QJsonObject message(int id, qint64 sequence, const QString &sender) {
    QJsonObject value;
    value["id"] = id;
    value["roomId"] = 7;
    value["sender"] = sender;
    value["senderName"] = sender.toUpper();
    value["content"] = QStringLiteral("hello");
    value["contentType"] = QStringLiteral("text");
    value["timestamp"] = 1700000000000.0;
    value["sequence"] = static_cast<double>(sequence);
    value["clientMessageId"] = QStringLiteral("client-%1").arg(id);
    return value;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);

    QJsonObject roomMessage = message(10, 3, QStringLiteral("alice"));
    roomMessage["syncSequence"] = 5;
    roomMessage["contentType"] = QStringLiteral("image");
    roomMessage["fileId"] = 80;
    roomMessage["fileName"] = QStringLiteral("photo.png");
    roomMessage["fileSize"] = 12;
    roomMessage["thumbnail"] = QStringLiteral("dGh1bWI=");
    QJsonObject deletion;
    deletion["eventType"] = QStringLiteral("messagesDeleted");
    deletion["syncSequence"] = 6;
    QJsonObject room;
    room["success"] = true;
    room["roomId"] = 7;
    room["mode"] = QStringLiteral("sequence");
    room["messages"] = QJsonArray{roomMessage};
    room["events"] = QJsonArray{deletion};
    room["nextSequence"] = 6;
    room["hasMore"] = true;
    const auto roomPage = V1HistoryPageAdapter::parseRoom(
        room, QStringLiteral("alice"));
    if (!check(roomPage.valid && roomPage.sequenceMode
                   && roomPage.messages.size() == 1,
               QStringLiteral("valid room page was rejected"))
        || !check(roomPage.messages.first().sequence() == 5
                      && roomPage.messages.first().isMine()
                      && roomPage.messages.first().deliveryState()
                          == Message::Accepted,
                  QStringLiteral("room message authority fields are wrong"))
        || !check(roomPage.messages.first().contentType() == Message::File
                      && roomPage.messages.first().thumbnail()
                          == QStringLiteral("dGh1bWI="),
                  QStringLiteral("attachment history was not normalized"))
        || !check(roomPage.observedSequences == QList<qint64>{5, 6}
                      && roomPage.nextSequence == 6 && roomPage.hasMore,
                  QStringLiteral("room continuation metadata is wrong"))) return 1;

    QJsonObject direct;
    direct["success"] = true;
    direct["friendUsername"] = QStringLiteral("bob");
    direct["friendshipId"] = 42;
    direct["messages"] = QJsonArray{message(11, 9, QStringLiteral("bob"))};
    const auto directPage = V1HistoryPageAdapter::parseDirect(
        direct, QStringLiteral("alice"));
    if (!check(directPage.valid && !directPage.sequenceMode
                   && directPage.peerUsername == QStringLiteral("bob")
                   && directPage.friendshipId == 42,
               QStringLiteral("legacy direct page was not parsed"))
        || !check(directPage.messages.first().timestamp().toMSecsSinceEpoch()
                      == 1700000000000LL,
                  QStringLiteral("direct timestamp was not normalized"))) return 1;

    QJsonObject denied;
    denied["success"] = false;
    denied["roomId"] = 7;
    denied["errorCode"] = QStringLiteral("ROOM_ACCESS_DENIED");
    denied["error"] = QStringLiteral("denied");
    const auto deniedPage = V1HistoryPageAdapter::parseRoom(
        denied, QStringLiteral("alice"));
    if (!check(!deniedPage.valid
                   && deniedPage.errorCode == QStringLiteral("ROOM_ACCESS_DENIED"),
               QStringLiteral("server rejection was accepted"))) return 1;

    QJsonArray oversized;
    for (int i = 0; i <= V1HistoryPageAdapter::MaxPageItems; ++i)
        oversized.append(message(i + 1, i + 1, QStringLiteral("bob")));
    direct["messages"] = oversized;
    if (!check(!V1HistoryPageAdapter::parseDirect(
                    direct, QStringLiteral("alice")).valid,
               QStringLiteral("oversized page was accepted"))) return 1;

    room["messages"] = QJsonArray{};
    room["events"] = QJsonArray{};
    room["nextSequence"] = -1;
    if (!check(!V1HistoryPageAdapter::parseRoom(
                    room, QStringLiteral("alice")).valid,
               QStringLiteral("negative continuation cursor was accepted")))
        return 1;

    room["messages"] = QJsonArray{roomMessage};
    room["nextSequence"] = 4;
    if (!check(!V1HistoryPageAdapter::parseRoom(
                    room, QStringLiteral("alice")).valid,
               QStringLiteral("page beyond continuation cursor was accepted")))
        return 1;

    qInfo() << "[V1HistoryPageAdapterTest] PASS";
    return 0;
}
