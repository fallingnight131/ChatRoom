#include "MessageModel.h"
#include "Protocol.h"

#include <QCoreApplication>
#include <QDebug>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    MessageModel model;

    Message live = Message::createTextMessage(1, QStringLiteral("alice"),
                                              QStringLiteral("hello"));
    live.setId(7);
    live.setClientMessageId(QStringLiteral("client-7"));
    model.addMessage(live);

    Message replay = live;
    replay.setContent(QStringLiteral("此消息已被撤回"));
    replay.setRecalled(true);
    replay.setSequence(1);

    Message older = Message::createTextMessage(1, QStringLiteral("bob"),
                                               QStringLiteral("older"));
    older.setId(6);
    older.setSequence(0);
    model.prependMessages({older, replay, older});

    QJsonObject selected;
    selected["eventType"] = QStringLiteral("messagesDeleted");
    selected["mode"] = QStringLiteral("selected");
    selected["messageIds"] = QJsonArray{6};
    model.applyDeletionEvents({selected, selected});

    bool passed = model.rowCount() == 1
        && model.messageAt(0).id() == 7
        && model.messageAt(0).recalled()
        && model.messageAt(0).content() == QStringLiteral("此消息已被撤回");

    MessageModel predicateModel;
    Message at100 = Message::createTextMessage(1, QStringLiteral("a"), QStringLiteral("100"));
    at100.setId(100);
    at100.setTimestamp(100);
    Message at200 = Message::createTextMessage(1, QStringLiteral("b"), QStringLiteral("200"));
    at200.setId(200);
    at200.setTimestamp(200);
    Message at300 = Message::createTextMessage(1, QStringLiteral("c"), QStringLiteral("300"));
    at300.setId(300);
    at300.setTimestamp(300);
    predicateModel.prependMessages({at100, at200, at300});
    QJsonObject before;
    before["mode"] = QStringLiteral("before");
    before["cutoffMs"] = 200;
    predicateModel.applyDeletionEvents({before});
    passed = passed && predicateModel.rowCount() == 2
        && predicateModel.messageAt(0).id() == 200;
    QJsonObject after;
    after["mode"] = QStringLiteral("after");
    after["timestamp"] = 200;
    predicateModel.applyDeletionEvents({after});
    passed = passed && predicateModel.rowCount() == 1
        && predicateModel.messageAt(0).id() == 200;
    QJsonObject all;
    all["mode"] = QStringLiteral("all");
    predicateModel.applyDeletionEvents({all, all});
    passed = passed && predicateModel.rowCount() == 0;

    MessageModel syncModel;
    at100.setSequence(1);
    syncModel.addMessage(at100);
    at300.setSequence(3);
    QJsonObject clearAtTwo;
    clearAtTwo["mode"] = QStringLiteral("all");
    clearAtTwo["syncSequence"] = 2;
    syncModel.reconcileSyncPage({at300}, {clearAtTwo});
    passed = passed && syncModel.rowCount() == 1
        && syncModel.messageAt(0).id() == 300;

    const QJsonObject friendResume = Protocol::makeFriendHistoryAfterSequenceReq(
        QStringLiteral("bob"), 41, 75);
    const QJsonObject friendResumeData = friendResume["data"].toObject();
    passed = passed
        && friendResume["type"].toString() == Protocol::MsgType::FRIEND_HISTORY_REQ
        && friendResumeData["friendUsername"].toString() == QStringLiteral("bob")
        && friendResumeData["afterSequence"].toInt() == 41
        && friendResumeData["count"].toInt() == 75;

    MessageModel optimisticModel;
    Message optimistic = Message::createTextMessage(1, QStringLiteral("alice"),
                                                    QStringLiteral("pending"));
    optimistic.setClientMessageId(QStringLiteral("optimistic-1"));
    optimistic.setDeliveryState(Message::Sending);
    optimisticModel.addMessage(optimistic);
    Message authoritative = optimistic;
    authoritative.setId(501);
    authoritative.setSequence(9);
    authoritative.setDeliveryState(Message::Accepted);
    optimisticModel.addMessage(authoritative);
    passed = passed && optimisticModel.rowCount() == 1
        && optimisticModel.messageAt(0).id() == 501
        && optimisticModel.messageAt(0).deliveryState() == Message::Accepted;

    Message failed = Message::createTextMessage(1, QStringLiteral("alice"),
                                                QStringLiteral("retry"));
    failed.setClientMessageId(QStringLiteral("optimistic-2"));
    failed.setDeliveryState(Message::Sending);
    optimisticModel.addMessage(failed);
    optimisticModel.updateDeliveryState(QStringLiteral("optimistic-2"), Message::Failed);
    passed = passed && optimisticModel.messageAt(1).deliveryState() == Message::Failed;
    optimisticModel.acceptOutgoing(QStringLiteral("optimistic-2"), 502, 10, 12345);
    passed = passed && optimisticModel.messageAt(1).id() == 502
        && optimisticModel.messageAt(1).sequence() == 10
        && optimisticModel.messageAt(1).deliveryState() == Message::Accepted;

    MessageModel cacheModel;
    cacheModel.addMessage(authoritative);
    Message pendingCache = Message::createTextMessage(
        1, QStringLiteral("alice"), QStringLiteral("pending-cache"));
    pendingCache.setClientMessageId(QStringLiteral("pending-cache"));
    pendingCache.setDeliveryState(Message::Sending);
    cacheModel.addMessage(pendingCache);
    Message failedCache = Message::createTextMessage(
        1, QStringLiteral("alice"), QStringLiteral("failed-cache"));
    failedCache.setClientMessageId(QStringLiteral("failed-cache"));
    failedCache.setDeliveryState(Message::Failed);
    cacheModel.addMessage(failedCache);
    cacheModel.discardCachedHistory();
    passed = passed && cacheModel.rowCount() == 2
        && cacheModel.messageAt(0).clientMessageId() == QStringLiteral("pending-cache")
        && cacheModel.messageAt(1).clientMessageId() == QStringLiteral("failed-cache");
    if (!passed) qCritical() << "Message model reconciliation verification failed";
    return passed ? 0 : 1;
}
