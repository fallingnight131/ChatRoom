#include "FriendMessageService.h"

#include "InputValidator.h"

namespace {
constexpr int kMaxClientMessageIdBytes = 128;

FriendMessageService::Result rejected(FriendMessageService::Status status,
                                      const QString &code,
                                      const QString &message) {
    FriendMessageService::Result result;
    result.status = status;
    result.errorCode = code;
    result.error = message;
    return result;
}
}

FriendMessageService::FriendMessageService(DatabaseManager *database)
    : m_database(database) {}

FriendMessageService::Result FriendMessageService::submit(const Command &command) const {
    if (!m_database || command.senderId <= 0) {
        return rejected(Status::Unauthorized, QStringLiteral("FRIENDSHIP_ACCESS_DENIED"),
                        QStringLiteral("无权向该用户发送消息"));
    }

    const int friendId = m_database->getUserIdByName(command.friendUsername);
    const int friendshipId = friendId > 0
                                 ? m_database->getFriendshipId(command.senderId, friendId)
                                 : -1;
    if (friendshipId < 0) {
        return rejected(Status::Unauthorized, QStringLiteral("FRIENDSHIP_ACCESS_DENIED"),
                        QStringLiteral("无权向该用户发送消息"));
    }

    QString validationError;
    if (!InputValidator::validateMessage(command.content, command.contentType,
                                         &validationError)) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_MESSAGE"),
                        validationError);
    }
    if (command.clientMessageId.isEmpty() ||
        command.clientMessageId.toUtf8().size() > kMaxClientMessageIdBytes) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_CLIENT_MESSAGE_ID"),
                        QStringLiteral("clientMessageId 必须为 1 到 128 字节"));
    }

    const MessageSaveResult stored = m_database->saveFriendMessageIdempotent(
        friendshipId, command.senderId, command.clientMessageId,
        command.content, command.contentType);

    Result result;
    result.friendshipId = friendshipId;
    result.messageId = stored.messageId;
    result.sequence = stored.sequence;
    result.createdAtMs = stored.createdAtMs;
    switch (stored.status) {
    case MessageSaveResult::Status::Created:
        result.status = Status::Accepted;
        break;
    case MessageSaveResult::Status::Duplicate:
        result.status = Status::Duplicate;
        break;
    case MessageSaveResult::Status::Conflict:
        result.status = Status::Conflict;
        result.errorCode = QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT");
        result.error = QStringLiteral("clientMessageId 已用于不同消息");
        break;
    case MessageSaveResult::Status::Failed:
        result.status = Status::StorageFailure;
        result.errorCode = QStringLiteral("MESSAGE_PERSISTENCE_FAILED");
        result.error = QStringLiteral("消息保存失败，请稍后重试");
        break;
    }
    return result;
}
