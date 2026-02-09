#include "MessageModel.h"

MessageModel::MessageModel(QObject *parent)
    : QAbstractListModel(parent)
{
}

int MessageModel::rowCount(const QModelIndex &parent) const {
    Q_UNUSED(parent)
    return m_messages.size();
}

QVariant MessageModel::data(const QModelIndex &index, int role) const {
    if (!index.isValid() || index.row() >= m_messages.size())
        return {};

    const Message &msg = m_messages[index.row()];

    switch (role) {
    case Qt::DisplayRole:
    case ContentRole:     return msg.content();
    case IdRole:          return msg.id();
    case SenderRole:      return msg.sender();
    case ContentTypeRole: return static_cast<int>(msg.contentType());
    case TimestampRole:   return msg.timestamp();
    case RecalledRole:    return msg.recalled();
    case IsMineRole:      return msg.isMine();
    case FileNameRole:    return msg.fileName();
    case FileSizeRole:    return msg.fileSize();
    case FileIdRole:      return msg.fileId();
    case ImageDataRole:   return msg.imageData();
    case RoomIdRole:      return msg.roomId();
    }
    return {};
}

QHash<int, QByteArray> MessageModel::roleNames() const {
    return {
        { IdRole,          "msgId" },
        { SenderRole,      "sender" },
        { ContentRole,     "content" },
        { ContentTypeRole, "contentType" },
        { TimestampRole,   "timestamp" },
        { RecalledRole,    "recalled" },
        { IsMineRole,      "isMine" },
        { FileNameRole,    "fileName" },
        { FileSizeRole,    "fileSize" },
        { FileIdRole,      "fileId" },
        { ImageDataRole,   "imageData" },
        { RoomIdRole,      "roomId" },
    };
}

void MessageModel::addMessage(const Message &msg) {
    beginInsertRows(QModelIndex(), m_messages.size(), m_messages.size());
    m_messages.append(msg);
    endInsertRows();
}

void MessageModel::prependMessages(const QList<Message> &msgs) {
    if (msgs.isEmpty()) return;
    beginInsertRows(QModelIndex(), 0, msgs.size() - 1);
    for (int i = msgs.size() - 1; i >= 0; --i)
        m_messages.prepend(msgs[i]);
    endInsertRows();
}

void MessageModel::recallMessage(int messageId) {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].id() == messageId) {
            m_messages[i].setRecalled(true);
            QModelIndex idx = index(i);
            emit dataChanged(idx, idx, { RecalledRole, ContentRole });
            break;
        }
    }
}

void MessageModel::clear() {
    beginResetModel();
    m_messages.clear();
    endResetModel();
}

const Message &MessageModel::messageAt(int row) const {
    return m_messages[row];
}

int MessageModel::findMessageRow(int messageId) const {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].id() == messageId)
            return i;
    }
    return -1;
}
