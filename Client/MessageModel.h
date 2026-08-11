#pragma once

#include <QAbstractListModel>
#include <QJsonArray>
#include <QList>
#include "Message.h"

/// 消息列表模型 —— Model/View 架构
class MessageModel : public QAbstractListModel {
    Q_OBJECT
public:
    static constexpr int MaxResolvedMessages = 500;

    enum MessageRole {
        IdRole = Qt::UserRole + 1,
        SenderRole,
        ContentRole,
        ContentTypeRole,
        TimestampRole,
        RecalledRole,
        IsMineRole,
        FileNameRole,
        FileSizeRole,
        FileIdRole,
        ImageDataRole,
        RoomIdRole,
        DownloadStateRole,
        DownloadProgressRole,
        SenderNameRole,
        FileClearedRole,
        ClearReasonRole,
        SequenceRole,
        ClientMessageIdRole,
        DeliveryStateRole
    };

    explicit MessageModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;
    QHash<int, QByteArray> roleNames() const override;

    void addMessage(const Message &msg);
    void prependMessages(const QList<Message> &msgs);
    void reconcileSyncPage(const QList<Message> &messages, const QJsonArray &events);
    void recallMessage(int messageId);
    void applyDeletionEvents(const QJsonArray &events);
    void clear();
    void discardCachedHistory();

    const Message &messageAt(int row) const;
    const QList<Message> &messages() const { return m_messages; }
    int findMessageRow(int messageId) const;
    int findMessageByFileId(int fileId) const;
    int findMessageByClientMessageId(const QString &clientMessageId) const;
    void updateDownloadProgress(int fileId, int state, double progress);
    void removeMessageByFileId(int fileId);
    void updateSenderName(const QString &username, const QString &newDisplayName);
    void updateSenderUid(const QString &oldUid, const QString &newUid);
    void markFilesCleared(const QList<int> &fileIds, const QString &reason);
    void updateDeliveryState(const QString &clientMessageId,
                             Message::DeliveryState state);
    void acceptOutgoing(const QString &clientMessageId, int messageId,
                        qint64 sequence, qint64 timestamp);

private:
    static bool isUnresolvedSend(const Message &message);
    void enforceRetentionLimit();

    QList<Message> m_messages;
};
