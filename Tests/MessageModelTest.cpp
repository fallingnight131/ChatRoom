#include "MessageModel.h"

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
    if (!passed) qCritical() << "Message model reconciliation verification failed";
    return passed ? 0 : 1;
}
