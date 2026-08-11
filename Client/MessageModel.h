#pragma once

#include <QAbstractListModel>
#include <QJsonArray>
#include <QList>
#include "Message.h"

/// 消息列表模型 —— Model/View 架构
class MessageModel : public QAbstractListModel {
    Q_OBJECT
public:
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
        ClientMessageIdRole
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

    const Message &messageAt(int row) const;
    const QList<Message> &messages() const { return m_messages; }
    int findMessageRow(int messageId) const;
    int findMessageByFileId(int fileId) const;
    void updateDownloadProgress(int fileId, int state, double progress);
    void removeMessageByFileId(int fileId);
    void updateSenderName(const QString &username, const QString &newDisplayName);
    void updateSenderUid(const QString &oldUid, const QString &newUid);
    void markFilesCleared(const QList<int> &fileIds, const QString &reason);

private:
    QList<Message> m_messages;
};
