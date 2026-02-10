#pragma once

#include <QAbstractListModel>
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
        RoomIdRole
    };

    explicit MessageModel(QObject *parent = nullptr);

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role = Qt::DisplayRole) const override;
    QHash<int, QByteArray> roleNames() const override;

    void addMessage(const Message &msg);
    void prependMessages(const QList<Message> &msgs);
    void recallMessage(int messageId);
    void clear();

    const Message &messageAt(int row) const;
    int findMessageRow(int messageId) const;
    int findMessageByFileId(int fileId) const;

private:
    QList<Message> m_messages;
};
