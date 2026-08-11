#include "V1HistoryPageAdapter.h"

#include "Protocol.h"

namespace {
void reject(V1HistoryPageAdapter::Page *page, const QString &code,
            const QString &message) {
    page->valid = false;
    page->errorCode = code;
    page->error = message;
}
}

V1HistoryPageAdapter::Page V1HistoryPageAdapter::parseRoom(
    const QJsonObject &data, const QString &currentUsername) {
    Page page;
    page.kind = Kind::Room;
    page.roomId = data["roomId"].toInt();
    if (!validateEnvelope(data, &page)) return page;
    if (page.roomId <= 0) {
        reject(&page, QStringLiteral("INVALID_ROOM_HISTORY"),
               QStringLiteral("history response has no valid room"));
        return page;
    }

    const QJsonArray rawMessages = data["messages"].toArray();
    page.events = data["events"].toArray();
    if (rawMessages.size() > MaxPageItems || page.events.size() > MaxPageItems
        || (page.sequenceMode
            && rawMessages.size() + page.events.size() > MaxPageItems)) {
        reject(&page, QStringLiteral("OVERSIZED_HISTORY_PAGE"),
               QStringLiteral("history response exceeds the supported page bound"));
        return page;
    }
    for (const QJsonValue &value : rawMessages) {
        const QJsonObject object = value.toObject();
        page.messages.append(parseMessage(object, currentUsername,
                                          page.sequenceMode));
        page.observedSequences.append(syncSequence(object));
    }
    for (const QJsonValue &value : page.events)
        page.observedSequences.append(syncSequence(value.toObject()));
    if (!validateSequencePage(&page)) return page;
    page.valid = true;
    return page;
}

V1HistoryPageAdapter::Page V1HistoryPageAdapter::parseDirect(
    const QJsonObject &data, const QString &currentUsername) {
    Page page;
    page.kind = Kind::Direct;
    page.peerUsername = data["friendUsername"].toString();
    page.friendshipId = data["friendshipId"].toInt();
    if (!validateEnvelope(data, &page)) return page;
    if (page.peerUsername.isEmpty()) {
        reject(&page, QStringLiteral("INVALID_DIRECT_HISTORY"),
               QStringLiteral("history response has no direct-message peer"));
        return page;
    }

    const QJsonArray rawMessages = data["messages"].toArray();
    if (rawMessages.size() > MaxPageItems) {
        reject(&page, QStringLiteral("OVERSIZED_HISTORY_PAGE"),
               QStringLiteral("history response exceeds the supported page bound"));
        return page;
    }
    for (const QJsonValue &value : rawMessages) {
        const QJsonObject object = value.toObject();
        page.messages.append(parseMessage(object, currentUsername,
                                          page.sequenceMode));
        page.observedSequences.append(syncSequence(object));
    }
    if (!validateSequencePage(&page)) return page;
    page.valid = true;
    return page;
}

qint64 V1HistoryPageAdapter::syncSequence(const QJsonObject &data) {
    qint64 sequence = data["sequence"].toVariant().toLongLong();
    sequence = qMax(sequence,
                    data["mutationSequence"].toVariant().toLongLong());
    return qMax(sequence,
                data["syncSequence"].toVariant().toLongLong());
}

Message V1HistoryPageAdapter::parseMessage(
    const QJsonObject &data, const QString &currentUsername,
    bool sequenceMode) {
    QJsonObject envelope;
    envelope["type"] = data["contentType"].toString() == QStringLiteral("system")
        ? Protocol::MsgType::SYSTEM_MSG : Protocol::MsgType::CHAT_MSG;
    envelope["timestamp"] = data["timestamp"];
    envelope["data"] = data;
    Message message = Message::fromJson(envelope);
    if (sequenceMode) message.setSequence(syncSequence(data));
    message.setIsMine(message.sender() == currentUsername);
    message.setDeliveryState(Message::Accepted);
    if (message.contentType() == Message::Image && message.fileId() != 0)
        message.setContentType(Message::File);
    return message;
}

bool V1HistoryPageAdapter::validateEnvelope(
    const QJsonObject &data, Page *page) {
    if (data.contains("success") && !data["success"].toBool()) {
        reject(page, data["errorCode"].toString(QStringLiteral("HISTORY_REJECTED")),
               data["error"].toString(QStringLiteral("history request rejected")));
        return false;
    }
    if (!data.contains("messages") || !data["messages"].isArray()) {
        reject(page, QStringLiteral("MALFORMED_HISTORY_PAGE"),
               QStringLiteral("history response has no message array"));
        return false;
    }
    page->sequenceMode = data["mode"].toString() == QStringLiteral("sequence");
    if (page->sequenceMode) {
        page->nextSequence = data["nextSequence"].toVariant().toLongLong();
        page->hasMore = data["hasMore"].toBool();
        if (page->nextSequence < 0) {
            reject(page, QStringLiteral("INVALID_SEQUENCE_CURSOR"),
                   QStringLiteral("history response has a negative cursor"));
            return false;
        }
    }
    return true;
}

bool V1HistoryPageAdapter::validateSequencePage(Page *page) {
    if (!page->sequenceMode) return true;
    qint64 observedMaximum = 0;
    for (qint64 sequence : page->observedSequences)
        observedMaximum = qMax(observedMaximum, sequence);
    if (observedMaximum > page->nextSequence) {
        reject(page, QStringLiteral("INCONSISTENT_SEQUENCE_PAGE"),
               QStringLiteral("history page contains data beyond its continuation cursor"));
        return false;
    }
    return true;
}
