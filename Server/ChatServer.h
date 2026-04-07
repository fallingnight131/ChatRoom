#pragma once

#include <QTcpServer>
#include <QMap>
#include <QSet>
#include <QMutex>
#include <QJsonObject>
#include <QJsonArray>
#include <QFile>
#include <QDateTime>

class QWebSocketServer;
class QWebSocket;
class QTcpSocket;
class QTimer;
class ClientSession;
class DatabaseManager;
class RoomManager;
class CosManager;

/// 聊天服务器 —— 管理所有客户端连接和消息路由
class ChatServer : public QTcpServer {
    Q_OBJECT
public:
    explicit ChatServer(QObject *parent = nullptr);
    ~ChatServer() override;

    bool startServer(quint16 port, quint16 wsPort = 0, quint16 httpPort = 0);
    void stopServer();

    DatabaseManager *database() const { return m_db; }
    RoomManager     *roomManager() const { return m_roomMgr; }
    CosManager      *cosManager() const { return m_cos; }

    // 向房间内所有成员广播消息
    void broadcastToRoom(int roomId, const QJsonObject &msg, ClientSession *exclude = nullptr);

    // 向指定用户发送消息
    void sendToUser(const QString &username, const QJsonObject &msg);

    // 获取指定房间在线用户列表
    QStringList onlineUsersInRoom(int roomId) const;

protected:
    void incomingConnection(qintptr socketDescriptor) override;

private slots:
    void onClientAuthenticated(ClientSession *session);
    void onClientDisconnected(ClientSession *session);
    void onClientMessage(ClientSession *session, const QJsonObject &msg);
    void onNewWebSocketConnection();
    void handleHttpRequest(QTcpSocket *socket);

private:
    bool setupHttpServer(quint16 port);
    QString generateFileToken(int userId);
    int validateFileToken(const QString &token) const;
    bool validateDeveloperKey(const QString &providedKey, QString *error = nullptr) const;

    void handleLogin(ClientSession *session, const QJsonObject &data);
    void handleRegister(ClientSession *session, const QJsonObject &data);
    void handleChatMessage(ClientSession *session, const QJsonObject &msg);
    void handleCreateRoom(ClientSession *session, const QJsonObject &data);
    void handleJoinRoom(ClientSession *session, const QJsonObject &data);
    void handleLeaveRoom(ClientSession *session, const QJsonObject &data);
    void handleRoomList(ClientSession *session);
    void handleUserList(ClientSession *session, const QJsonObject &data);
    void handleHistory(ClientSession *session, const QJsonObject &data);
    void handleFileSend(ClientSession *session, const QJsonObject &msg);
    void handleFileDownload(ClientSession *session, const QJsonObject &data);
    void handleFileUploadStart(ClientSession *session, const QJsonObject &data);
    void handleFileUploadChunk(ClientSession *session, const QJsonObject &data);
    void handleFileUploadEnd(ClientSession *session, const QJsonObject &data);
    void handleFileDownloadChunk(ClientSession *session, const QJsonObject &data);
    void handleRecall(ClientSession *session, const QJsonObject &data);
    void handleSetAdmin(ClientSession *session, const QJsonObject &data);
    void handleDeleteMessages(ClientSession *session, const QJsonObject &data);
    void handleRoomSettings(ClientSession *session, const QJsonObject &data);
    void handleRoomFiles(ClientSession *session, const QJsonObject &data);
    void handleRoomFilesDelete(ClientSession *session, const QJsonObject &data);
    void handleDeleteRoom(ClientSession *session, const QJsonObject &data);
    void handleRenameRoom(ClientSession *session, const QJsonObject &data);
    void handleSetRoomPassword(ClientSession *session, const QJsonObject &data);
    void handleGetRoomPassword(ClientSession *session, const QJsonObject &data);
    void handleKickUser(ClientSession *session, const QJsonObject &data);
    void handleAvatarUpload(ClientSession *session, const QJsonObject &data);
    void handleAvatarGet(ClientSession *session, const QJsonObject &data);
    void handleFileUploadCancel(ClientSession *session, const QJsonObject &data);
    void handleChangeNickname(ClientSession *session, const QJsonObject &data);
    void handleChangeUid(ClientSession *session, const QJsonObject &data);
    void handleChangePassword(ClientSession *session, const QJsonObject &data);

    // 用户搜索
    void handleUserSearch(ClientSession *session, const QJsonObject &data);

    // 聊天室搜索
    void handleRoomSearch(ClientSession *session, const QJsonObject &data);

    // 聊天室头像
    void handleRoomAvatarUpload(ClientSession *session, const QJsonObject &data);
    void handleRoomAvatarGet(ClientSession *session, const QJsonObject &data);

    // 好友系统
    void handleFriendRequest(ClientSession *session, const QJsonObject &data);
    void handleFriendAccept(ClientSession *session, const QJsonObject &data);
    void handleFriendReject(ClientSession *session, const QJsonObject &data);
    void handleFriendRemove(ClientSession *session, const QJsonObject &data);
    void handleFriendList(ClientSession *session);
    void handleFriendPending(ClientSession *session);
    void handleFriendChatMessage(ClientSession *session, const QJsonObject &msg);
    void handleFriendHistory(ClientSession *session, const QJsonObject &data);
    void handleFriendFileSend(ClientSession *session, const QJsonObject &msg);
    void handleFriendFileUploadStart(ClientSession *session, const QJsonObject &data);
    void handleFriendRecall(ClientSession *session, const QJsonObject &data);
    bool tryReserveRoomFileQuota(int roomId, qint64 fileSize, QString *error);
    void releaseRoomFileQuota(int roomId, qint64 fileSize);
    QList<int> buildCleanupPlan(int roomId, qint64 newMaxFileSize, qint64 newTotalFileSpace,
                                int newMaxFileCount, QJsonObject *planSummary);
    bool applyFileCleanupPlan(int roomId, const QList<int> &fileIds, const QString &reason, QJsonArray *clearedIdsOut);

    /// 根据文件名返回类型子目录 ("Image", "Video", "File")
    static QString fileTypeSubDir(const QString &fileName);
    /// 构建服务端文件存放目录：server_files/{roomId}/Image|Video|File/{yyyy-MM}/
    QString serverFileDir(int roomId, const QString &fileName) const;
    /// 构建好友文件存放目录：server_files/friends/{friendshipId}/Image|Video|File/{yyyy-MM}/
    QString friendFileDir(int friendshipId, const QString &fileName) const;

    /// 异步上传文件到 COS，发送进度给上传者
    void startCosUpload(const QString &localPath, const QString &fileName,
                        const QString &dirPrefix, int fileId, bool isFriendFile,
                        const QString &uploaderUsername, const QString &uploadId);

    /// 批量删除 COS 对象（fire-and-forget，COS 未启用时为空操作）
    void deleteCosFiles(const QStringList &cosUrls);

    DatabaseManager *m_db       = nullptr;
    RoomManager     *m_roomMgr  = nullptr;
    CosManager      *m_cos      = nullptr;
    QWebSocketServer *m_wsServer = nullptr;
    QTcpServer      *m_httpServer = nullptr;
    QTimer          *m_expireTimer = nullptr;
    quint16          m_httpPort = 0;
    QMap<QString, QPair<int, QDateTime>> m_fileTokens; // token -> {userId, expireAt(UTC)}

    mutable QMutex m_mutex;
    QMap<QString, ClientSession*> m_sessions;  // username -> session

    // 大文件上传临时状态
    struct UploadState {
        int roomId = 0;
        int userId = 0;
        QString username;
        QString displayName;
        QString fileName;
        QString filePath;    // 临时文件路径
        qint64 fileSize = 0;
        qint64 received = 0;
        bool roomQuotaReserved = false;
        QFile *file = nullptr;
    };
    QMap<QString, UploadState> m_uploads;  // uploadId -> state
    QMap<int, qint64> m_roomReservedBytes;  // roomId -> reserved bytes by in-flight uploads
    QMap<int, int> m_roomReservedCount;     // roomId -> reserved file count by in-flight uploads
};
