#pragma once

#include <QList>
#include <QMap>
#include <QSet>
#include <QString>

#include "LocalConversationRepository.h"

class AttachmentOutboxService {
public:
    struct Target {
        LocalConversationRepository::Kind kind = LocalConversationRepository::Kind::Room;
        QString conversationKey;
        int roomId = 0;
        QString peerUsername;
    };

    struct Command {
        Target target;
        QString clientMessageId;
        QString sourcePath;
        QString fileName;
        QString contentType;
        qint64 fileSize = 0;
    };

    explicit AttachmentOutboxService(
        LocalConversationRepository *repository = nullptr);

    void setRepository(LocalConversationRepository *repository);

    static Target roomTarget(int roomId);
    static Target directTarget(const QString &conversationKey,
                               const QString &peerUsername);

    bool stage(const QString &account, const Target &target,
               const QString &sourcePath, const QString &contentType,
               Command *command);
    QList<Command> recoverRooms(const QString &account,
                                const QSet<int> &allowedRoomIds);
    QList<Command> recoverDirects(
        const QString &account,
        const QMap<QString, QString> &peerByConversationKey);
    bool prepareRetry(const QString &account, const Target &target,
                      const QString &clientMessageId, Command *command);
    bool replaceSource(const QString &account, const QString &clientMessageId,
                       const QString &sourcePath);

    bool recordUploading(const QString &account,
                         const QString &clientMessageId);
    bool recordPendingAuthorization(const QString &account,
                                    const QString &clientMessageId);
    bool recordProgress(const QString &account,
                        const QString &clientMessageId, qint64 transmittedBytes);
    bool recordFinalizing(const QString &account,
                          const QString &clientMessageId, qint64 transmittedBytes);
    bool recordFailed(const QString &account, const QString &clientMessageId,
                      const QString &failureCode);
    bool complete(const QString &account, const QString &clientMessageId);
    bool cancel(const QString &account, const QString &clientMessageId);

    QString lastError() const { return m_lastError; }

private:
    bool validateTarget(const Target &target);
    bool validateSource(const LocalConversationRepository::AttachmentCommand &stored,
                        QString *failureCode);
    bool makeCommand(const Target &target,
                     const LocalConversationRepository::AttachmentCommand &stored,
                     Command *command);
    QList<Command> recover(
        const QString &account, LocalConversationRepository::Kind kind,
        const QSet<int> &allowedRoomIds,
        const QMap<QString, QString> &peerByConversationKey);
    static QString sourceFingerprint(const QString &path, qint64 fileSize);

    LocalConversationRepository *m_repository = nullptr;
    QString m_lastError;
};
