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
    case DownloadStateRole:    return static_cast<int>(msg.downloadState());
    case DownloadProgressRole: return msg.downloadProgress();
    case SenderNameRole:  return msg.senderName();
    case FileClearedRole: return msg.fileCleared();
    case ClearReasonRole: return msg.clearReason();
    case SequenceRole:    return msg.sequence();
    case ClientMessageIdRole: return msg.clientMessageId();
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
        { DownloadStateRole, "downloadState" },
        { DownloadProgressRole, "downloadProgress" },
        { SenderNameRole,  "senderName" },
        { FileClearedRole, "fileCleared" },
        { ClearReasonRole, "clearReason" },
        { SequenceRole,    "sequence" },
        { ClientMessageIdRole, "clientMessageId" },
    };
}

void MessageModel::addMessage(const Message &msg) {
    if (msg.id() > 0 && findMessageRow(msg.id()) >= 0) return;
    beginInsertRows(QModelIndex(), m_messages.size(), m_messages.size());
    m_messages.append(msg);
    endInsertRows();
}

void MessageModel::prependMessages(const QList<Message> &msgs) {
    QList<Message> unique;
    for (const Message &message : msgs) {
        int existingRow = message.id() > 0 ? findMessageRow(message.id()) : -1;
        if (existingRow < 0 && !message.clientMessageId().isEmpty()) {
            for (int i = 0; i < m_messages.size(); ++i) {
                if (m_messages[i].clientMessageId() == message.clientMessageId()) {
                    existingRow = i;
                    break;
                }
            }
        }
        if (existingRow >= 0) {
            m_messages[existingRow] = message;
            const QModelIndex changed = index(existingRow);
            emit dataChanged(changed, changed);
            continue;
        }

        int pendingRow = -1;
        for (int i = 0; i < unique.size(); ++i) {
            const bool sameId = message.id() > 0 && unique[i].id() == message.id();
            const bool sameClientId = !message.clientMessageId().isEmpty()
                && unique[i].clientMessageId() == message.clientMessageId();
            if (sameId || sameClientId) {
                pendingRow = i;
                break;
            }
        }
        if (pendingRow >= 0) unique[pendingRow] = message;
        else unique.append(message);
    }
    if (unique.isEmpty()) return;
    beginInsertRows(QModelIndex(), 0, unique.size() - 1);
    for (int i = unique.size() - 1; i >= 0; --i)
        m_messages.prepend(unique[i]);
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

int MessageModel::findMessageByFileId(int fileId) const {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].fileId() == fileId)
            return i;
    }
    return -1;
}

void MessageModel::updateDownloadProgress(int fileId, int state, double progress) {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].fileId() == fileId) {
            m_messages[i].setDownloadState(static_cast<Message::DownloadState>(state));
            m_messages[i].setDownloadProgress(progress);
            QModelIndex idx = index(i);
            emit dataChanged(idx, idx, { DownloadStateRole, DownloadProgressRole });
            break;
        }
    }
}

void MessageModel::removeMessageByFileId(int fileId) {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].fileId() == fileId) {
            beginRemoveRows(QModelIndex(), i, i);
            m_messages.removeAt(i);
            endRemoveRows();
            break;
        }
    }
}

void MessageModel::updateSenderName(const QString &username, const QString &newDisplayName) {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].sender() == username) {
            m_messages[i].setSenderName(newDisplayName);
            QModelIndex idx = index(i);
            emit dataChanged(idx, idx, { SenderNameRole });
        }
    }
}

void MessageModel::updateSenderUid(const QString &oldUid, const QString &newUid) {
    for (int i = 0; i < m_messages.size(); ++i) {
        if (m_messages[i].sender() == oldUid) {
            m_messages[i].setSender(newUid);
            QModelIndex idx = index(i);
            emit dataChanged(idx, idx, { SenderRole });
        }
    }
}

void MessageModel::markFilesCleared(const QList<int> &fileIds, const QString &reason) {
    if (fileIds.isEmpty()) return;

    QSet<int> idSet;
    for (int id : fileIds) idSet.insert(id);

    for (int i = 0; i < m_messages.size(); ++i) {
        if (idSet.contains(m_messages[i].fileId())) {
            m_messages[i].setFileCleared(true);
            if (!reason.isEmpty())
                m_messages[i].setClearReason(reason);
            QModelIndex idx = index(i);
            emit dataChanged(idx, idx, { FileClearedRole, ClearReasonRole });
        }
    }
}
