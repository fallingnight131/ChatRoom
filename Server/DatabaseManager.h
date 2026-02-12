#pragma once

#include <QObject>
#include <QSqlDatabase>
#include <QJsonArray>
#include <QMutex>
#include <QPair>

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
    QJsonArray getUserJoinedRooms(int userId);
    bool deleteRoom(int roomId);
    QString getRoomName(int roomId);
    bool renameRoom(int roomId, const QString &newName);
    int getRoomMemberCount(int roomId);
    bool isUserInRoom(int roomId, int userId);

    // 房间密码
    bool setRoomPassword(int roomId, const QString &password);
    QString getRoomPassword(int roomId);
    bool roomHasPassword(int roomId);
    QJsonArray getRoomMembers(int roomId);
    bool leaveRoom(int roomId, int userId);
    int getUserIdByName(const QString &username);

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
    QString getFileName(int fileId);

    // 管理员管理
    bool isRoomAdmin(int roomId, int userId);
    bool isRoomCreator(int roomId, int userId);
    bool setRoomAdmin(int roomId, int userId, bool isAdmin);
    bool hasAnyAdmin(int roomId);
    QList<int> getRoomAdmins(int roomId);

    // 管理员操作 - 删除消息
    bool deleteMessages(int roomId, const QList<int> &messageIds);
    int  deleteAllMessages(int roomId);
    int  deleteMessagesBefore(int roomId, const QDateTime &before);
    int  deleteMessagesAfter(int roomId, const QDateTime &after);

    // 查询消息关联的文件ID和路径（用于删除前清理）
    QList<QPair<int, QString>> getFileInfoForMessages(int roomId, const QList<int> &messageIds);
    QList<QPair<int, QString>> getAllFileInfoForRoom(int roomId);
    QList<QPair<int, QString>> getFileInfoBeforeTime(int roomId, const QDateTime &before);
    QList<QPair<int, QString>> getFileInfoAfterTime(int roomId, const QDateTime &after);
    // 从files表删除记录
    bool deleteFileRecords(const QList<int> &fileIds);

    // 房间设置
    qint64 getRoomMaxFileSize(int roomId);
    bool   setRoomMaxFileSize(int roomId, qint64 maxSize);

    // 用户头像
    QByteArray getUserAvatar(int userId);
    bool       setUserAvatar(int userId, const QByteArray &avatarData);
    QByteArray getUserAvatarByName(const QString &username);

private:
    QSqlDatabase getConnection();
    QString hashPassword(const QString &password, const QString &salt);
    QString generateSalt();

    QString m_dbPath;   // SQLite 数据库文件路径

    QMutex m_initMutex;
    bool   m_initialized = false;
};
