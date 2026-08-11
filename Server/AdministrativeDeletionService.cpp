#include "AdministrativeDeletionService.h"

#include <QSet>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <algorithm>

namespace {
constexpr int kMaxClientOperationIdBytes = 128;
constexpr int kMaxSelectedMessageIds = 100;

AdministrativeDeletionService::Result rejected(
    AdministrativeDeletionService::Status status, const QString &code,
    const QString &message, const AdministrativeDeletionService::Command &command) {
    AdministrativeDeletionService::Result result;
    result.status = status;
    result.roomId = command.roomId;
    result.mode = command.mode;
    result.cutoffMs = command.cutoffMs;
    result.clientOperationId = command.clientOperationId;
    result.errorCode = code;
    result.error = message;
    return result;
}
}

AdministrativeDeletionService::AdministrativeDeletionService(DatabaseManager *database)
    : m_database(database) {}

AdministrativeDeletionService::Result AdministrativeDeletionService::execute(
    const Command &command) const {
    if (!m_database || command.roomId <= 0 || command.operatorUserId <= 0 ||
        !m_database->isRoomAdmin(command.roomId, command.operatorUserId)) {
        return rejected(Status::Unauthorized, QStringLiteral("ADMIN_DELETE_ACCESS_DENIED"),
                        QStringLiteral("您没有管理员权限"), command);
    }
    if (command.clientOperationId.isEmpty() ||
        command.clientOperationId.toUtf8().size() > kMaxClientOperationIdBytes) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_CLIENT_OPERATION_ID"),
                        QStringLiteral("clientOperationId 必须为 1 到 128 字节"), command);
    }
    static const QSet<QString> modes = {
        QStringLiteral("selected"), QStringLiteral("all"),
        QStringLiteral("before"), QStringLiteral("after")
    };
    if (!modes.contains(command.mode)) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_DELETE_MODE"),
                        QStringLiteral("删除模式无效"), command);
    }
    if ((command.mode == QStringLiteral("before") ||
         command.mode == QStringLiteral("after")) && command.cutoffMs <= 0) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_DELETE_CUTOFF"),
                        QStringLiteral("删除时间无效"), command);
    }
    const qint64 effectiveCutoffMs =
        (command.mode == QStringLiteral("before") ||
         command.mode == QStringLiteral("after"))
        ? (command.cutoffMs / 1000) * 1000
        : 0;

    QList<int> normalizedIds;
    QSet<int> seen;
    for (int messageId : command.messageIds) {
        if (messageId <= 0 || seen.contains(messageId)) continue;
        seen.insert(messageId);
        normalizedIds.append(messageId);
    }
    std::sort(normalizedIds.begin(), normalizedIds.end());
    QList<int> normalizedFileIds;
    seen.clear();
    for (int fileId : command.sourceFileIds) {
        if (fileId <= 0 || seen.contains(fileId)) continue;
        seen.insert(fileId);
        normalizedFileIds.append(fileId);
    }
    std::sort(normalizedFileIds.begin(), normalizedFileIds.end());
    if (command.mode == QStringLiteral("selected") &&
        ((normalizedIds.isEmpty() && normalizedFileIds.isEmpty()) ||
         (!normalizedIds.isEmpty() && !normalizedFileIds.isEmpty()) ||
         normalizedIds.size() > kMaxSelectedMessageIds ||
         normalizedFileIds.size() > kMaxSelectedMessageIds)) {
        return rejected(Status::Invalid, QStringLiteral("INVALID_MESSAGE_SELECTION"),
                        QStringLiteral("必须按一种方式选择 1 到 100 个有效目标"), command);
    }
    if (command.mode != QStringLiteral("selected") &&
        (!normalizedIds.isEmpty() || !normalizedFileIds.isEmpty())) {
        return rejected(Status::Invalid, QStringLiteral("UNEXPECTED_MESSAGE_SELECTION"),
                        QStringLiteral("该删除模式不能携带消息列表"), command);
    }

    QJsonArray fingerprintIds;
    const QList<int> &selectedIds = normalizedFileIds.isEmpty()
        ? normalizedIds : normalizedFileIds;
    for (int value : selectedIds) fingerprintIds.append(value);
    QJsonObject fingerprintObject;
    fingerprintObject["mode"] = command.mode;
    fingerprintObject["cutoff"] = static_cast<double>(effectiveCutoffMs);
    fingerprintObject["selection"] = normalizedFileIds.isEmpty()
        ? (normalizedIds.isEmpty() ? QStringLiteral("predicate")
                                   : QStringLiteral("messages"))
        : QStringLiteral("files");
    fingerprintObject["ids"] = fingerprintIds;
    const QString commandFingerprint = QString::fromLatin1(
        QCryptographicHash::hash(
            QJsonDocument(fingerprintObject).toJson(QJsonDocument::Compact),
            QCryptographicHash::Sha256).toHex());

    const AdministrativeDeletionSaveResult stored =
        m_database->saveAdministrativeDeletion(
            command.roomId, command.operatorUserId, command.operatorName,
            command.clientOperationId, commandFingerprint, command.mode,
            normalizedIds, normalizedFileIds, effectiveCutoffMs);

    Result result;
    result.roomId = stored.roomId > 0 ? stored.roomId : command.roomId;
    result.mode = stored.mode.isEmpty() ? command.mode : stored.mode;
    result.clientOperationId = command.clientOperationId;
    result.deletedCount = stored.deletedCount;
    result.sequence = stored.sequence;
    result.cutoffMs = stored.cutoffMs;
    result.createdAtMs = stored.createdAtMs;
    result.messageIds = stored.messageIds;
    result.deletedFileIds = stored.deletedFileIds;
    switch (stored.status) {
    case AdministrativeDeletionSaveResult::Status::Created:
        result.status = Status::Accepted;
        break;
    case AdministrativeDeletionSaveResult::Status::Duplicate:
        result.status = Status::Duplicate;
        break;
    case AdministrativeDeletionSaveResult::Status::Conflict:
        result.status = Status::Conflict;
        result.errorCode = QStringLiteral("CLIENT_OPERATION_ID_CONFLICT");
        result.error = QStringLiteral("clientOperationId 已用于不同删除命令");
        break;
    case AdministrativeDeletionSaveResult::Status::Failed:
        result.status = Status::StorageFailure;
        result.errorCode = QStringLiteral("ADMIN_DELETE_PERSISTENCE_FAILED");
        result.error = QStringLiteral("删除消息失败，请稍后重试");
        break;
    }
    return result;
}
