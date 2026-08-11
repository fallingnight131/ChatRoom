#pragma once

#include <QJsonArray>
#include <QJsonObject>
#include <QList>
#include <QString>

#include "Message.h"

class V1HistoryPageAdapter {
public:
    enum class Kind { Room, Direct };

    struct Page {
        Kind kind = Kind::Room;
        bool valid = false;
        QString errorCode;
        QString error;
        int roomId = 0;
        QString peerUsername;
        int friendshipId = 0;
        bool sequenceMode = false;
        QList<Message> messages;
        QJsonArray events;
        QList<qint64> observedSequences;
        qint64 nextSequence = 0;
        bool hasMore = false;
    };

    static constexpr int MaxPageItems = 100;

    static Page parseRoom(const QJsonObject &data,
                          const QString &currentUsername);
    static Page parseDirect(const QJsonObject &data,
                            const QString &currentUsername);

private:
    static qint64 syncSequence(const QJsonObject &data);
    static Message parseMessage(const QJsonObject &data,
                                const QString &currentUsername,
                                bool sequenceMode);
    static bool validateEnvelope(const QJsonObject &data, Page *page);
    static bool validateSequencePage(Page *page);
};
