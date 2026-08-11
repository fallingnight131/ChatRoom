#include "RoomMessageService.h"

#include "InputValidator.h"

namespace {
constexpr int kMaxClientMessageIdBytes = 128;

RoomMessageService::Result rejected(RoomMessageService::Status status,
                                    const QString &code,
                                    const QString &message) {
    RoomMessageService::Result result;
    result.status = status;
    result.errorCode = code;
    result.error = message;
    return result;
}
}

RoomMessageService::RoomMessageService(DatabaseManager *database)
    : m_database(database) {}

RoomMessageService::Result RoomMessageService::submit(const Command &command) const {
    if (!m_database || command.senderId <= 0 || command.roomId <= 0 ||
        !m_database->isUserInRoom(command.roomId, command.senderId)) {
        return rejected(Status::Unauthorized, QStringLiteral("ROOM_ACCESS_DENIED"),
                        QStringLiteral("无权向该聊天室发送消息"));
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

    const RoomMessageSaveResult stored = m_database->saveRoomMessageIdempotent(
        command.roomId, command.senderId, command.clientMessageId,
        command.content, command.contentType);

    Result result;
    result.messageId = stored.messageId;
    result.sequence = stored.sequence;
    result.createdAtMs = stored.createdAtMs;
    switch (stored.status) {
    case RoomMessageSaveResult::Status::Created:
        result.status = Status::Accepted;
        break;
    case RoomMessageSaveResult::Status::Duplicate:
        result.status = Status::Duplicate;
        break;
    case RoomMessageSaveResult::Status::Conflict:
        result.status = Status::Conflict;
        result.errorCode = QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT");
        result.error = QStringLiteral("clientMessageId 已用于不同消息");
        break;
    case RoomMessageSaveResult::Status::Failed:
        result.status = Status::StorageFailure;
        result.errorCode = QStringLiteral("MESSAGE_PERSISTENCE_FAILED");
        result.error = QStringLiteral("消息保存失败，请稍后重试");
        break;
    }
    return result;
}
