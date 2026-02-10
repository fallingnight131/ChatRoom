#pragma once

#include <QObject>
#include <QSqlDatabase>
#include <QJsonArray>
#include <QMutex>

/// 数据库管理器 —— 线程安全，使用每线程独立连接
class DatabaseManager : public QObject {
    Q_OBJECT
public:
    explicit DatabaseManager(QObject *parent = nullptr);
    ~DatabaseManager() override;

    bool initialize();

    // 用户管理
    int  registerUser(const QString &username, const QString &password);
    int  authenticateUser(const QString &username, const QString &password);

    // 房间管理
    int  createRoom(const QString &name, int creatorId);
    bool joinRoom(int roomId, int userId);
    QJsonArray getAllRooms();

    // 消息管理
    int  saveMessage(int roomId, int userId, const QString &content,
                     const QString &contentType,
                     const QString &fileName = QString(),
                     qint64 fileSize = 0, int fileId = 0);
    QJsonArray getMessageHistory(int roomId, int count, qint64 beforeTimestamp = 0);
    bool recallMessage(int messageId, int userId, int timeLimitSec);

    // 文件管理
    int     saveFile(int roomId, int userId, const QString &fileName,
                     const QString &filePath, qint64 fileSize);
    QString getFilePath(int fileId);

    // 管理员管理
    bool isRoomAdmin(int roomId, int userId);
    bool isRoomCreator(int roomId, int userId);
    bool setRoomAdmin(int roomId, int userId, bool isAdmin);
    QList<int> getRoomAdmins(int roomId);

    // 管理员操作 - 删除消息
    bool deleteMessages(int roomId, const QList<int> &messageIds);
    int  deleteAllMessages(int roomId);
    int  deleteMessagesBefore(int roomId, const QDateTime &before);
    int  deleteMessagesAfter(int roomId, const QDateTime &after);

private:
    QSqlDatabase getConnection();
    QString hashPassword(const QString &password, const QString &salt);
    QString generateSalt();

    QString m_dbPath;   // SQLite 数据库文件路径

    QMutex m_initMutex;
    bool   m_initialized = false;
};
