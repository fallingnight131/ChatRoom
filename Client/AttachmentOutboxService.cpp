#include "AttachmentOutboxService.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QUuid>

namespace {
constexpr qint64 FingerprintEdgeBytes = 64 * 1024;
}

AttachmentOutboxService::AttachmentOutboxService(
    LocalConversationRepository *repository)
    : m_repository(repository) {
}

void AttachmentOutboxService::setRepository(
    LocalConversationRepository *repository) {
    m_repository = repository;
    m_lastError.clear();
}

AttachmentOutboxService::Target AttachmentOutboxService::roomTarget(int roomId) {
    Target target;
    target.kind = LocalConversationRepository::Kind::Room;
    target.roomId = roomId;
    target.conversationKey = QString::number(roomId);
    return target;
}

AttachmentOutboxService::Target AttachmentOutboxService::directTarget(
    const QString &conversationKey, const QString &peerUsername) {
    Target target;
    target.kind = LocalConversationRepository::Kind::Direct;
    target.conversationKey = conversationKey;
    target.peerUsername = peerUsername;
    return target;
}

bool AttachmentOutboxService::stage(
    const QString &account, const Target &target, const QString &sourcePath,
    const QString &contentType, Command *command) {
    m_lastError.clear();
    if (!m_repository || !command || account.isEmpty()
        || contentType.isEmpty() || !validateTarget(target)) {
        if (m_lastError.isEmpty())
            m_lastError = QStringLiteral("attachment command store is unavailable or invalid");
        return false;
    }

    const QFileInfo source(sourcePath);
    if (!source.exists() || !source.isFile() || !source.isReadable()
        || source.size() <= 0) {
        m_lastError = QStringLiteral("attachment source is unavailable");
        return false;
    }
    const QString fingerprint = sourceFingerprint(source.absoluteFilePath(), source.size());
    if (fingerprint.isEmpty()) {
        m_lastError = QStringLiteral("attachment source cannot be fingerprinted");
        return false;
    }

    LocalConversationRepository::AttachmentCommand stored;
    stored.kind = target.kind;
    stored.conversationKey = target.conversationKey;
    stored.clientMessageId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    stored.sourcePath = source.absoluteFilePath();
    stored.fileName = source.fileName();
    stored.contentType = contentType;
    stored.fileSize = source.size();
    stored.sourceModifiedAtMs = source.lastModified().toMSecsSinceEpoch();
    stored.sourceFingerprint = fingerprint;
    if (!m_repository->upsertAttachmentCommand(account, stored)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    return makeCommand(target, stored, command);
}

QList<AttachmentOutboxService::Command> AttachmentOutboxService::recoverRooms(
    const QString &account, const QSet<int> &allowedRoomIds) {
    return recover(account, LocalConversationRepository::Kind::Room,
                   allowedRoomIds, {});
}

QList<AttachmentOutboxService::Command> AttachmentOutboxService::recoverDirects(
    const QString &account,
    const QMap<QString, QString> &peerByConversationKey) {
    return recover(account, LocalConversationRepository::Kind::Direct,
                   {}, peerByConversationKey);
}

bool AttachmentOutboxService::prepareRetry(
    const QString &account, const Target &target,
    const QString &clientMessageId, Command *command) {
    m_lastError.clear();
    if (!m_repository || account.isEmpty() || clientMessageId.isEmpty()
        || !command || !validateTarget(target)) {
        if (m_lastError.isEmpty())
            m_lastError = QStringLiteral("invalid attachment retry identity");
        return false;
    }
    const auto storedCommands = m_repository->attachmentCommands(account, target.kind);
    if (!m_repository->lastError().isEmpty()) {
        m_lastError = m_repository->lastError();
        return false;
    }
    for (const auto &stored : storedCommands) {
        if (stored.clientMessageId != clientMessageId
            || stored.conversationKey != target.conversationKey) continue;
        QString failureCode;
        if (!validateSource(stored, &failureCode)) {
            recordFailed(account, clientMessageId, failureCode);
            if (m_lastError.isEmpty()) m_lastError = failureCode;
            return false;
        }
        if (!m_repository->updateAttachmentCommandState(
                account, clientMessageId,
                LocalConversationRepository::AttachmentState::PendingAuthorization, 0)) {
            m_lastError = m_repository->lastError();
            return false;
        }
        return makeCommand(target, stored, command);
    }
    m_lastError = QStringLiteral("attachment command not found");
    return false;
}

bool AttachmentOutboxService::replaceSource(
    const QString &account, const QString &clientMessageId,
    const QString &sourcePath) {
    m_lastError.clear();
    if (!m_repository || account.isEmpty() || clientMessageId.isEmpty()) {
        m_lastError = QStringLiteral("invalid attachment source replacement identity");
        return false;
    }
    const QFileInfo source(sourcePath);
    if (!source.exists() || !source.isFile() || !source.isReadable()
        || source.size() <= 0) {
        m_lastError = QStringLiteral("replacement attachment source is unavailable");
        return false;
    }
    const QString fingerprint = sourceFingerprint(source.absoluteFilePath(), source.size());
    if (fingerprint.isEmpty()) {
        m_lastError = QStringLiteral("replacement attachment source cannot be fingerprinted");
        return false;
    }
    for (LocalConversationRepository::Kind kind : {
             LocalConversationRepository::Kind::Room,
             LocalConversationRepository::Kind::Direct}) {
        const auto storedCommands = m_repository->attachmentCommands(account, kind);
        if (!m_repository->lastError().isEmpty()) {
            m_lastError = m_repository->lastError();
            return false;
        }
        for (auto stored : storedCommands) {
            if (stored.clientMessageId != clientMessageId) continue;
            stored.sourcePath = source.absoluteFilePath();
            stored.fileName = source.fileName();
            stored.fileSize = source.size();
            stored.sourceModifiedAtMs = source.lastModified().toMSecsSinceEpoch();
            stored.sourceFingerprint = fingerprint;
            stored.state = LocalConversationRepository::AttachmentState::PendingAuthorization;
            stored.transmittedBytes = 0;
            stored.failureCode.clear();
            if (!m_repository->upsertAttachmentCommand(account, stored)) {
                m_lastError = m_repository->lastError();
                return false;
            }
            return true;
        }
    }
    m_lastError = QStringLiteral("attachment command not found");
    return false;
}

bool AttachmentOutboxService::recordUploading(
    const QString &account, const QString &clientMessageId) {
    return recordProgress(account, clientMessageId, 0);
}

bool AttachmentOutboxService::recordPendingAuthorization(
    const QString &account, const QString &clientMessageId) {
    m_lastError.clear();
    if (!m_repository || !m_repository->updateAttachmentCommandState(
            account, clientMessageId,
            LocalConversationRepository::AttachmentState::PendingAuthorization, 0)) {
        m_lastError = m_repository ? m_repository->lastError()
                                   : QStringLiteral("attachment command store unavailable");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::recordProgress(
    const QString &account, const QString &clientMessageId,
    qint64 transmittedBytes) {
    m_lastError.clear();
    if (!m_repository || !m_repository->updateAttachmentCommandState(
            account, clientMessageId,
            LocalConversationRepository::AttachmentState::Uploading,
            transmittedBytes)) {
        m_lastError = m_repository ? m_repository->lastError()
                                   : QStringLiteral("attachment command store unavailable");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::recordFinalizing(
    const QString &account, const QString &clientMessageId,
    qint64 transmittedBytes) {
    m_lastError.clear();
    if (!m_repository || !m_repository->updateAttachmentCommandState(
            account, clientMessageId,
            LocalConversationRepository::AttachmentState::Finalizing,
            transmittedBytes)) {
        m_lastError = m_repository ? m_repository->lastError()
                                   : QStringLiteral("attachment command store unavailable");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::recordFailed(
    const QString &account, const QString &clientMessageId,
    const QString &failureCode) {
    m_lastError.clear();
    const QString boundedCode = failureCode.isEmpty()
        ? QStringLiteral("ATTACHMENT_SEND_FAILED") : failureCode.left(128);
    if (!m_repository || !m_repository->updateAttachmentCommandState(
            account, clientMessageId,
            LocalConversationRepository::AttachmentState::Failed, 0,
            boundedCode)) {
        m_lastError = m_repository ? m_repository->lastError()
                                   : QStringLiteral("attachment command store unavailable");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::complete(
    const QString &account, const QString &clientMessageId) {
    m_lastError.clear();
    if (!m_repository
        || !m_repository->removeAttachmentCommand(account, clientMessageId)) {
        m_lastError = m_repository ? m_repository->lastError()
                                   : QStringLiteral("attachment command store unavailable");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::cancel(
    const QString &account, const QString &clientMessageId) {
    return complete(account, clientMessageId);
}

bool AttachmentOutboxService::validateTarget(const Target &target) {
    if (target.conversationKey.isEmpty()) {
        m_lastError = QStringLiteral("missing attachment conversation key");
        return false;
    }
    if (target.kind == LocalConversationRepository::Kind::Room) {
        if (target.roomId <= 0) {
            m_lastError = QStringLiteral("invalid attachment room target");
            return false;
        }
    } else if (target.peerUsername.isEmpty()) {
        m_lastError = QStringLiteral("missing attachment direct-message peer");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::validateSource(
    const LocalConversationRepository::AttachmentCommand &stored,
    QString *failureCode) {
    const QFileInfo source(stored.sourcePath);
    if (!source.exists() || !source.isFile() || !source.isReadable()) {
        if (failureCode) *failureCode = QStringLiteral("SOURCE_UNAVAILABLE");
        return false;
    }
    if (source.size() != stored.fileSize
        || source.lastModified().toMSecsSinceEpoch() != stored.sourceModifiedAtMs
        || sourceFingerprint(source.absoluteFilePath(), source.size())
            != stored.sourceFingerprint) {
        if (failureCode) *failureCode = QStringLiteral("SOURCE_CHANGED");
        return false;
    }
    return true;
}

bool AttachmentOutboxService::makeCommand(
    const Target &target,
    const LocalConversationRepository::AttachmentCommand &stored,
    Command *command) {
    if (!command || stored.clientMessageId.isEmpty()) {
        m_lastError = QStringLiteral("missing attachment command identity");
        return false;
    }
    command->target = target;
    command->clientMessageId = stored.clientMessageId;
    command->sourcePath = stored.sourcePath;
    command->fileName = stored.fileName;
    command->contentType = stored.contentType;
    command->fileSize = stored.fileSize;
    return true;
}

QList<AttachmentOutboxService::Command> AttachmentOutboxService::recover(
    const QString &account, LocalConversationRepository::Kind kind,
    const QSet<int> &allowedRoomIds,
    const QMap<QString, QString> &peerByConversationKey) {
    QList<Command> commands;
    m_lastError.clear();
    if (!m_repository || account.isEmpty()) return commands;
    const auto storedCommands = m_repository->attachmentCommands(account, kind);
    if (!m_repository->lastError().isEmpty()) {
        m_lastError = m_repository->lastError();
        return commands;
    }
    for (const auto &stored : storedCommands) {
        if (stored.state == LocalConversationRepository::AttachmentState::Failed)
            continue;
        Target target;
        if (kind == LocalConversationRepository::Kind::Room) {
            bool ok = false;
            const int roomId = stored.conversationKey.toInt(&ok);
            if (!ok || roomId <= 0 || !allowedRoomIds.contains(roomId)) continue;
            target = roomTarget(roomId);
        } else {
            const QString peer = peerByConversationKey.value(stored.conversationKey);
            if (peer.isEmpty()) continue;
            target = directTarget(stored.conversationKey, peer);
        }

        QString failureCode;
        if (!validateSource(stored, &failureCode)) {
            if (!recordFailed(account, stored.clientMessageId, failureCode)) return {};
            continue;
        }
        if (!m_repository->updateAttachmentCommandState(
                account, stored.clientMessageId,
                LocalConversationRepository::AttachmentState::PendingAuthorization, 0)) {
            m_lastError = m_repository->lastError();
            return {};
        }
        Command command;
        if (makeCommand(target, stored, &command)) commands.append(command);
    }
    return commands;
}

QString AttachmentOutboxService::sourceFingerprint(
    const QString &path, qint64 fileSize) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return {};
    QCryptographicHash hash(QCryptographicHash::Sha256);
    hash.addData(QByteArray::number(fileSize));
    const QByteArray first = file.read(FingerprintEdgeBytes);
    if (first.isEmpty() && fileSize > 0) return {};
    hash.addData(first);
    if (fileSize > FingerprintEdgeBytes) {
        if (!file.seek(qMax<qint64>(0, fileSize - FingerprintEdgeBytes))) return {};
        hash.addData(file.read(FingerprintEdgeBytes));
    }
    return QString::fromLatin1(hash.result().toHex());
}
