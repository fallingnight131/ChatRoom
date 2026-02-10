#include "RoomManager.h"
#include "DatabaseManager.h"
#include <QJsonArray>
#include <QJsonObject>
#include <QDebug>

RoomManager::RoomManager(QObject *parent)
    : QObject(parent)
{
}

void RoomManager::loadRooms(DatabaseManager *db) {
    QJsonArray rooms = db->getAllRooms();
    QMutexLocker locker(&m_mutex);

    for (const QJsonValue &v : rooms) {
        QJsonObject r = v.toObject();
        int id = r["roomId"].toInt();
        RoomInfo info;
        info.name      = r["roomName"].toString();
        info.creatorId = r["creatorId"].toInt();
        m_rooms[id]    = info;
    }
    qInfo() << "[RoomMgr] 加载了" << m_rooms.size() << "个房间";
}

void RoomManager::addRoom(int roomId, const QString &name, int creatorId) {
    QMutexLocker locker(&m_mutex);
    RoomInfo info;
    info.name      = name;
    info.creatorId = creatorId;
    m_rooms[roomId] = info;
}

void RoomManager::removeRoom(int roomId) {
    QMutexLocker locker(&m_mutex);
    m_rooms.remove(roomId);
}

bool RoomManager::roomExists(int roomId) const {
    QMutexLocker locker(&m_mutex);
    return m_rooms.contains(roomId);
}

QString RoomManager::roomName(int roomId) const {
    QMutexLocker locker(&m_mutex);
    return m_rooms.value(roomId).name;
}

QMap<int, QString> RoomManager::allRooms() const {
    QMutexLocker locker(&m_mutex);
    QMap<int, QString> result;
    for (auto it = m_rooms.constBegin(); it != m_rooms.constEnd(); ++it)
        result[it.key()] = it.value().name;
    return result;
}

void RoomManager::addUserToRoom(int roomId, int userId, const QString &username) {
    QMutexLocker locker(&m_mutex);
    if (m_rooms.contains(roomId))
        m_rooms[roomId].members[userId] = username;
}

void RoomManager::removeUserFromRoom(int roomId, int userId) {
    QMutexLocker locker(&m_mutex);
    if (m_rooms.contains(roomId))
        m_rooms[roomId].members.remove(userId);
}

bool RoomManager::isUserInRoom(int roomId, int userId) const {
    QMutexLocker locker(&m_mutex);
    if (m_rooms.contains(roomId))
        return m_rooms[roomId].members.contains(userId);
    return false;
}

QStringList RoomManager::usersInRoom(int roomId) const {
    QMutexLocker locker(&m_mutex);
    QStringList list;
    if (m_rooms.contains(roomId)) {
        for (auto it = m_rooms[roomId].members.constBegin();
             it != m_rooms[roomId].members.constEnd(); ++it) {
            list.append(it.value());
        }
    }
    return list;
}

QList<int> RoomManager::userRooms(int userId) const {
    QMutexLocker locker(&m_mutex);
    QList<int> rooms;
    for (auto it = m_rooms.constBegin(); it != m_rooms.constEnd(); ++it) {
        if (it.value().members.contains(userId))
            rooms.append(it.key());
    }
    return rooms;
}
