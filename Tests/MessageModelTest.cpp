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

    const bool passed = model.rowCount() == 2
        && model.messageAt(0).id() == 6
        && model.messageAt(1).id() == 7
        && model.messageAt(1).recalled()
        && model.messageAt(1).content() == QStringLiteral("此消息已被撤回");
    if (!passed) qCritical() << "Message model reconciliation verification failed";
    return passed ? 0 : 1;
}
