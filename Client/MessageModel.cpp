#include "MessageModel.h"

#include <QJsonObject>
#include <QSet>
#include <algorithm>

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
    case DeliveryStateRole: return static_cast<int>(msg.deliveryState());
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
        { DeliveryStateRole, "deliveryState" },
    };
}

void MessageModel::addMessage(const Message &msg) {
    int existingRow = msg.id() > 0 ? findMessageRow(msg.id()) : -1;
    if (existingRow < 0 && !msg.clientMessageId().isEmpty()) {
        for (int i = 0; i < m_messages.size(); ++i) {
            if (m_messages[i].clientMessageId() == msg.clientMessageId()) {
                existingRow = i;
                break;
            }
        }
    }
    if (existingRow >= 0) {
        m_messages[existingRow] = msg;
        emit dataChanged(index(existingRow), index(existingRow));
        return;
    }
    beginInsertRows(QModelIndex(), m_messages.size(), m_messages.size());
    m_messages.append(msg);
    endInsertRows();
}

void MessageModel::discardCachedHistory() {
    QList<Message> unresolved;
    for (const Message &message : m_messages) {
        if (message.id() <= 0 && !message.clientMessageId().isEmpty())
            unresolved.append(message);
    }
    beginResetModel();
    m_messages = unresolved;
    endResetModel();
}

void MessageModel::updateDeliveryState(const QString &clientMessageId,
                                       Message::DeliveryState state) {
    for (int row = 0; row < m_messages.size(); ++row) {
        if (m_messages[row].clientMessageId() != clientMessageId) continue;
        m_messages[row].setDeliveryState(state);
        emit dataChanged(index(row), index(row), {DeliveryStateRole});
        return;
    }
}

void MessageModel::acceptOutgoing(const QString &clientMessageId, int messageId,
                                  qint64 sequence, qint64 timestamp) {
    for (int row = 0; row < m_messages.size(); ++row) {
        Message &message = m_messages[row];
        if (message.clientMessageId() != clientMessageId) continue;
        message.setId(messageId);
        message.setSequence(sequence);
        if (timestamp > 0) message.setTimestamp(timestamp);
        message.setDeliveryState(Message::Accepted);
        emit dataChanged(index(row), index(row));
        return;
    }
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

void MessageModel::reconcileSyncPage(const QList<Message> &messages,
                                     const QJsonArray &events) {
    struct SyncItem {
        qint64 sequence = 0;
        bool isEvent = false;
        Message message;
        QJsonObject event;
    };
    QList<SyncItem> items;
    for (const Message &message : messages)
        items.append({message.sequence(), false, message, {}});
    for (const QJsonValue &value : events) {
        const QJsonObject event = value.toObject();
        const qint64 sequence = event.contains("syncSequence")
            ? event["syncSequence"].toVariant().toLongLong()
            : event["sequence"].toVariant().toLongLong();
        items.append({sequence, true, {}, event});
    }
    std::sort(items.begin(), items.end(), [](const SyncItem &left,
                                             const SyncItem &right) {
        return left.sequence < right.sequence;
    });

    for (const SyncItem &item : items) {
        if (item.isEvent) {
            applyDeletionEvents({item.event});
            continue;
        }
        const Message &message = item.message;
        int row = message.id() > 0 ? findMessageRow(message.id()) : -1;
        if (row < 0 && !message.clientMessageId().isEmpty()) {
            for (int i = 0; i < m_messages.size(); ++i) {
                if (m_messages[i].clientMessageId() == message.clientMessageId()) {
                    row = i;
                    break;
                }
            }
        }
        if (row >= 0) {
            m_messages[row] = message;
            emit dataChanged(index(row), index(row));
        } else {
            beginInsertRows(QModelIndex(), m_messages.size(), m_messages.size());
            m_messages.append(message);
            endInsertRows();
        }
    }
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

void MessageModel::applyDeletionEvents(const QJsonArray &events) {
    for (const QJsonValue &value : events) {
        const QJsonObject event = value.toObject();
        if (event.contains("eventType") &&
            event["eventType"].toString() != QStringLiteral("messagesDeleted")) {
            continue;
        }
        const QString mode = event["mode"].toString();
        QSet<int> selectedIds;
        for (const QJsonValue &id : event["messageIds"].toArray())
            selectedIds.insert(id.toInt());
        const qint64 cutoff = event.contains("timestamp")
            ? event["timestamp"].toVariant().toLongLong()
            : event["cutoffMs"].toVariant().toLongLong();

        for (int row = m_messages.size() - 1; row >= 0; --row) {
            const Message &message = m_messages[row];
            const bool remove = mode == QStringLiteral("all") ||
                (mode == QStringLiteral("selected") && selectedIds.contains(message.id())) ||
                (mode == QStringLiteral("before") && cutoff > 0 &&
                 message.timestamp().toMSecsSinceEpoch() < cutoff) ||
                (mode == QStringLiteral("after") && cutoff > 0 &&
                 message.timestamp().toMSecsSinceEpoch() > cutoff);
            if (!remove) continue;
            beginRemoveRows(QModelIndex(), row, row);
            m_messages.removeAt(row);
            endRemoveRows();
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

int MessageModel::findMessageByClientMessageId(const QString &clientMessageId) const {
    for (int row = 0; row < m_messages.size(); ++row) {
        if (m_messages[row].clientMessageId() == clientMessageId) return row;
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
