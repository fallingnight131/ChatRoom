#pragma once

#include <QObject>
#include <QMap>
#include <QSet>
#include <QMutex>
#include <QPair>

class DatabaseManager;

/// 房间管理器 —— 管理聊天室及其在线成员（内存缓存）
class RoomManager : public QObject {
    Q_OBJECT
public:
    explicit RoomManager(QObject *parent = nullptr);

    void loadRooms(DatabaseManager *db);

    // 房间操作
    void addRoom(int roomId, const QString &name, int creatorId);
    bool roomExists(int roomId) const;
    QString roomName(int roomId) const;
    QMap<int, QString> allRooms() const;

    // 成员管理（在线缓存）
    void addUserToRoom(int roomId, int userId, const QString &username);
    void removeUserFromRoom(int roomId, int userId);
    bool isUserInRoom(int roomId, int userId) const;
    QStringList usersInRoom(int roomId) const;
    QList<int> userRooms(int userId) const;

private:
    mutable QMutex m_mutex;

    struct RoomInfo {
        QString name;
        int creatorId = 0;
        QMap<int, QString> members; // userId -> username (当前在线)
    };

    QMap<int, RoomInfo> m_rooms;
};
