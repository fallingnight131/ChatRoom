#include "OutgoingMessageService.h"

#include <QUuid>

OutgoingMessageService::OutgoingMessageService(
    LocalConversationRepository *repository)
    : m_repository(repository) {
}

void OutgoingMessageService::setRepository(
    LocalConversationRepository *repository) {
    m_repository = repository;
    m_lastError.clear();
}

OutgoingMessageService::Target OutgoingMessageService::roomTarget(int roomId) {
    Target target;
    target.kind = LocalConversationRepository::Kind::Room;
    target.roomId = roomId;
    target.conversationKey = QString::number(roomId);
    return target;
}

OutgoingMessageService::Target OutgoingMessageService::directTarget(
    const QString &conversationKey, const QString &peerUsername) {
    Target target;
    target.kind = LocalConversationRepository::Kind::Direct;
    target.conversationKey = conversationKey;
    target.peerUsername = peerUsername;
    return target;
}

bool OutgoingMessageService::stage(
    const QString &account, const Target &target, const QString &sender,
    const QString &senderName, const QString &content,
    Message::ContentType contentType, qint64 cursor, StagedSend *result) {
    m_lastError.clear();
    if (!result || account.isEmpty() || sender.isEmpty() || content.isEmpty()) {
        m_lastError = QStringLiteral("missing outgoing message identity or content");
        return false;
    }
    if (contentType != Message::Text && contentType != Message::Emoji) {
        m_lastError = QStringLiteral("only text and emoji use the optimistic outbox");
        return false;
    }
    if (!validateTarget(target)) return false;

    Message message = contentType == Message::Emoji
        ? Message::createEmojiMessage(target.roomId, sender, content)
        : Message::createTextMessage(target.roomId, sender, content);
    message.setSenderName(senderName);
    message.setClientMessageId(
        QUuid::createUuid().toString(QUuid::WithoutBraces));
    message.setIsMine(true);
    message.setDeliveryState(Message::Sending);

    Command command;
    if (!makeCommand(target, message, &command)) return false;
    persist(account, target, message, cursor);
    result->message = message;
    result->command = command;
    return true;
}

bool OutgoingMessageService::prepareRetry(
    const QString &account, const Target &target, const Message &message,
    qint64 cursor, Command *command) {
    m_lastError.clear();
    if (!validateTarget(target) || !makeCommand(target, message, command))
        return false;
    Message sending = message;
    sending.setDeliveryState(Message::Sending);
    persist(account, target, sending, cursor);
    return true;
}

QList<OutgoingMessageService::Command> OutgoingMessageService::recoverRooms(
    const QString &account, const QSet<int> &allowedRoomIds) {
    QList<Command> commands;
    m_lastError.clear();
    if (!m_repository || account.isEmpty()) return commands;
    const auto pending = m_repository->pendingSends(
        account, LocalConversationRepository::Kind::Room);
    for (const auto &send : pending) {
        bool ok = false;
        const int roomId = send.conversationKey.toInt(&ok);
        if (!ok || roomId <= 0 || !allowedRoomIds.contains(roomId)) continue;
        Command command;
        if (makeCommand(roomTarget(roomId), send.message, &command))
            commands.append(command);
    }
    if (!m_repository->lastError().isEmpty())
        m_lastError = m_repository->lastError();
    return commands;
}

QList<OutgoingMessageService::Command> OutgoingMessageService::recoverDirects(
    const QString &account,
    const QMap<QString, QString> &peerByConversationKey) {
    QList<Command> commands;
    m_lastError.clear();
    if (!m_repository || account.isEmpty()) return commands;
    const auto pending = m_repository->pendingSends(
        account, LocalConversationRepository::Kind::Direct);
    for (const auto &send : pending) {
        const QString peerUsername = peerByConversationKey.value(send.conversationKey);
        if (peerUsername.isEmpty()) continue;
        Command command;
        if (makeCommand(directTarget(send.conversationKey, peerUsername),
                        send.message, &command)) {
            commands.append(command);
        }
    }
    if (!m_repository->lastError().isEmpty())
        m_lastError = m_repository->lastError();
    return commands;
}

bool OutgoingMessageService::recordAccepted(
    const QString &account, const Target &target, Message *message,
    int messageId, qint64 sequence, qint64 timestamp) {
    m_lastError.clear();
    if (!message || message->clientMessageId().isEmpty()
        || messageId <= 0 || sequence <= 0 || !validateTarget(target)) {
        m_lastError = QStringLiteral("invalid authoritative send acceptance");
        return false;
    }
    message->setId(messageId);
    message->setSequence(sequence);
    if (timestamp > 0) message->setTimestamp(timestamp);
    message->setDeliveryState(Message::Accepted);
    return persist(account, target, *message, sequence);
}

bool OutgoingMessageService::recordFailed(
    const QString &account, const Target &target, Message *message,
    qint64 cursor) {
    m_lastError.clear();
    if (!message || message->clientMessageId().isEmpty()
        || !validateTarget(target)) {
        m_lastError = QStringLiteral("invalid failed send identity");
        return false;
    }
    message->setDeliveryState(Message::Failed);
    return persist(account, target, *message, cursor);
}

bool OutgoingMessageService::validateTarget(const Target &target) {
    if (target.conversationKey.isEmpty()) {
        m_lastError = QStringLiteral("missing conversation key");
        return false;
    }
    if (target.kind == LocalConversationRepository::Kind::Room) {
        if (target.roomId <= 0) {
            m_lastError = QStringLiteral("invalid room target");
            return false;
        }
    } else if (target.peerUsername.isEmpty()) {
        m_lastError = QStringLiteral("missing direct-message peer");
        return false;
    }
    return true;
}

bool OutgoingMessageService::persist(
    const QString &account, const Target &target, const Message &message,
    qint64 cursor) {
    if (!m_repository) return true;
    if (m_repository->upsertMessage(account, target.kind,
                                    target.conversationKey, message, cursor)) {
        return true;
    }
    m_lastError = m_repository->lastError();
    return false;
}

bool OutgoingMessageService::makeCommand(
    const Target &target, const Message &message, Command *command) {
    if (!command || message.clientMessageId().isEmpty()) {
        m_lastError = QStringLiteral("missing client message ID");
        return false;
    }
    if (message.contentType() != Message::Text
        && message.contentType() != Message::Emoji) {
        m_lastError = QStringLiteral("unsupported optimistic message type");
        return false;
    }
    command->target = target;
    command->content = message.content();
    command->contentType = Message::contentTypeToString(message.contentType());
    command->clientMessageId = message.clientMessageId();
    return true;
}
