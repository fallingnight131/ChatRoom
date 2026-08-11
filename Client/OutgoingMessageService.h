#pragma once

#include <QList>
#include <QMap>
#include <QSet>
#include <QString>

#include "LocalConversationRepository.h"

class OutgoingMessageService {
public:
    struct Target {
        LocalConversationRepository::Kind kind = LocalConversationRepository::Kind::Room;
        QString conversationKey;
        int roomId = 0;
        QString peerUsername;
    };

    struct Command {
        Target target;
        QString content;
        QString contentType;
        QString clientMessageId;
    };

    struct StagedSend {
        Message message;
        Command command;
    };

    explicit OutgoingMessageService(LocalConversationRepository *repository = nullptr);

    void setRepository(LocalConversationRepository *repository);

    static Target roomTarget(int roomId);
    static Target directTarget(const QString &conversationKey,
                               const QString &peerUsername);

    bool stage(const QString &account, const Target &target,
               const QString &sender, const QString &senderName,
               const QString &content, Message::ContentType contentType,
               qint64 cursor, StagedSend *result);
    bool prepareRetry(const QString &account, const Target &target,
                      const Message &message, qint64 cursor,
                      Command *command);
    QList<Command> recoverRooms(const QString &account,
                                const QSet<int> &allowedRoomIds);
    QList<Command> recoverDirects(
        const QString &account,
        const QMap<QString, QString> &peerByConversationKey);

    bool recordAccepted(const QString &account, const Target &target,
                        Message *message, int messageId, qint64 sequence,
                        qint64 timestamp);
    bool recordFailed(const QString &account, const Target &target,
                      Message *message, qint64 cursor);

    QString lastError() const { return m_lastError; }

private:
    bool validateTarget(const Target &target);
    bool persist(const QString &account, const Target &target,
                 const Message &message, qint64 cursor);
    bool makeCommand(const Target &target, const Message &message,
                     Command *command);

    LocalConversationRepository *m_repository = nullptr;
    QString m_lastError;
};
