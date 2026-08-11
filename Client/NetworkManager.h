#pragma once

#include <QObject>
#include <QTcpSocket>
#include <QSslSocket>
#include <QJsonObject>
#include <QJsonArray>
#include <QTimer>

class HttpUploadTransport;
class HttpDownloadTransport;

/// 网络管理器 —— 单例，管理与服务器的 TCP 连接
/// 观察者模式：通过信号通知各 UI 组件
class NetworkManager : public QObject {
    Q_OBJECT
public:
    static NetworkManager *instance();

    void connectToServer(const QString &host, quint16 port, bool useSsl = false);
    void disconnectFromServer();
    void sendMessage(const QJsonObject &msg);
    void loginWithCredentials(const QString &username, const QString &password);
    void changePassword(const QString &oldPassword, const QString &newPassword);
    bool uploadRawFile(const QString &uploadId, const QString &uploadPath,
                       const QString &filePath);
    void cancelRawUpload(const QString &uploadId);
    bool downloadRawFile(int fileId);
    void cancelRawDownload(int fileId);

    bool isConnected() const;
    bool supportsServerFileForward() const { return m_supportsServerFileForward; }
    QString currentUsername() const { return m_username; }
    int     currentUserId() const  { return m_userId; }

    void setCredentials(int userId, const QString &username);

signals:
    // 连接状态
    void connected();
    void disconnected();
    void connectionError(const QString &error);
    void reconnecting(int attempt);

    // 登录/注册响应
    void loginResponse(bool success, const QString &error, int userId, const QString &username, const QString &displayName);
    void registerResponse(bool success, const QString &error);

    // 聊天消息
    void chatMessageReceived(const QJsonObject &msg);
    void chatSendResponse(const QJsonObject &data);
    void systemMessageReceived(const QJsonObject &msg);

    // 房间
    void roomCreated(bool success, int roomId, const QString &roomName, const QString &error);
    void roomJoined(bool success, int roomId, const QString &roomName, const QString &error, bool newJoin = false);
    void roomListReceived(const QJsonArray &rooms);
    void userListReceived(int roomId, const QJsonArray &users);
    void userJoined(int roomId, const QString &username, const QString &displayName);
    void userLeft(int roomId, const QString &username, const QString &displayName);
    void userOnline(int roomId, const QString &username, const QString &displayName);
    void userOffline(int roomId, const QString &username, const QString &displayName);
    void leaveRoomResponse(bool success, int roomId);

    // 历史消息
    void historyReceived(const QJsonObject &data);

    // 文件
    void fileNotify(const QJsonObject &data);
    void fileDownloadReady(const QJsonObject &data);
    void fileForwardResponse(const QJsonObject &data);

    // 大文件分块传输
    void uploadStartResponse(const QJsonObject &data);
    void uploadChunkResponse(const QJsonObject &data);
    void uploadFinalizeResponse(const QJsonObject &data);
    void downloadChunkResponse(const QJsonObject &data);
    void fileCosProgress(const QJsonObject &data);
    void rawUploadProgress(const QString &uploadId, qint64 sent, qint64 total);
    void rawUploadFinished(const QString &uploadId, bool success, const QString &error);
    void rawDownloadProgress(int fileId, qint64 received, qint64 total);
    void rawDownloadFinished(int fileId, bool success,
                             const QString &temporaryPath,
                             const QString &error);

    // 撤回
    void recallResponse(bool success, int messageId, const QString &error);
    void recallNotify(const QJsonObject &data);

    // 强制下线
    void forceOffline(const QString &reason);

    // 管理员
    void adminStatusChanged(int roomId, bool isAdmin);
    void setAdminResponse(bool success, int roomId, const QString &username, const QString &error);
    void deleteMsgsResponse(const QJsonObject &data);
    void deleteMsgsNotify(const QJsonObject &data);

    // 头像
    void avatarUploadResponse(bool success, const QString &error);
    void avatarGetResponse(const QString &username, const QByteArray &avatarData);
    void avatarUpdateNotify(const QString &username, const QByteArray &avatarData);

    // 房间设置
    void roomSettingsResponse(int roomId, bool success, qint64 maxFileSize,
                              qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                                            const QString &error,
                                                            bool needConfirm, const QJsonObject &cleanupSummary,
                                                            const QJsonArray &clearedFileIds, int currentMembers);
    void roomSettingsNotify(int roomId, qint64 maxFileSize,
                                                        qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                                        const QJsonArray &clearedFileIds);

    // 房间文件管理
    void roomFilesResponse(bool success, int roomId, const QJsonArray &files,
                           qint64 usedFileSpace, qint64 maxFileSpace, const QString &error);
    void roomFilesDeleteResponse(bool success, int roomId, int deletedCount,
                                 const QJsonArray &clearedFileIds,
                                 qint64 usedFileSpace, qint64 maxFileSpace,
                                 const QString &error);
    void roomFilesNotify(int roomId, const QJsonArray &clearedFileIds,
                         qint64 usedFileSpace, qint64 maxFileSpace, const QString &operatorName);

    // 删除聊天室
    void deleteRoomResponse(bool success, int roomId, const QString &roomName, const QString &error);
    void deleteRoomNotify(int roomId, const QString &roomName, const QString &operatorName);

    // 重命名聊天室
    void renameRoomResponse(bool success, int roomId, const QString &newName, const QString &error);
    void renameRoomNotify(int roomId, const QString &newName);
    void setRoomPasswordResponse(bool success, int roomId, bool hasPassword, const QString &error);
    void getRoomPasswordResponse(bool success, int roomId, bool hasPassword,
                                 const QString &error);
    void joinRoomNeedPassword(int roomId);
    void kickUserResponse(bool success, int roomId, const QString &username, const QString &error);
    void kickedFromRoom(int roomId, const QString &roomName, const QString &operatorName);

    // 昵称修改
    void changeNicknameResponse(bool success, const QString &newDisplayName, const QString &error);
    void nicknameChangeNotify(int roomId, const QString &username, const QString &newDisplayName);

    // 修改用户ID
    void changeUidResponse(bool success, const QString &oldUid, const QString &newUid, const QString &error);
    void uidChangeNotify(int roomId, const QString &oldUid, const QString &newUid, const QString &displayName);

    // 修改密码
    void changePasswordResponse(bool success, const QString &error);

    // 用户搜索
    void userSearchResponse(bool success, const QJsonArray &users, const QString &error);

    // 聊天室搜索
    void roomSearchResponse(bool success, const QJsonArray &rooms, const QString &error);

    // 聊天室头像
    void roomAvatarUploadResponse(int roomId, bool success, const QString &error);
    void roomAvatarGetResponse(int roomId, bool success, const QByteArray &avatarData);
    void roomAvatarUpdateNotify(int roomId, const QByteArray &avatarData);

    // ========== 好友系统 ==========
    void friendRequestResponse(bool success, const QString &error);
    void friendRequestNotify(const QString &fromUsername, const QString &fromDisplayName);
    void friendAcceptResponse(bool success, const QString &error);
    void friendAcceptNotify(const QString &username, const QString &displayName);
    void friendRejectResponse(bool success, const QString &error);
    void friendRemoveResponse(bool success, const QString &username, const QString &error);
    void friendRemoveNotify(const QString &username, const QString &displayName);
    void friendListReceived(const QJsonArray &friends, int pendingFriendRequests);
    void friendPendingReceived(const QJsonArray &requests);
    void friendChatMessageReceived(const QJsonObject &data);
    void friendChatSendResponse(const QJsonObject &data);
    void friendHistoryReceived(const QJsonObject &data);
    void friendFileNotify(const QJsonObject &data);
    void friendOnlineNotify(const QString &username, const QString &displayName);
    void friendOfflineNotify(const QString &username);
    void friendReadNotify(const QJsonObject &data);
    void friendFileUploadStartResponse(const QJsonObject &data);
    void friendRecallResponse(bool success, int messageId, const QString &error);
    void friendRecallNotify(const QJsonObject &data);

private slots:
    void onConnected();
    void onDisconnected();
    void onReadyRead();
    void onError(QAbstractSocket::SocketError err);
    void onHeartbeat();
    void tryReconnect();

private:
    explicit NetworkManager(QObject *parent = nullptr);
    ~NetworkManager() override;

    void processMessage(const QJsonObject &msg);
    void openSocket();

    static NetworkManager *s_instance;

    QTcpSocket *m_socket          = nullptr;
    QTimer     *m_heartbeatTimer  = nullptr;
    QTimer     *m_reconnectTimer  = nullptr;
    QByteArray  m_buffer;
    HttpUploadTransport *m_httpUpload = nullptr;
    HttpDownloadTransport *m_httpDownload = nullptr;
    bool m_supportsServerFileForward = false;

    QString     m_host;
    quint16     m_port            = 0;
    bool        m_useSsl          = false;
    bool        m_autoReconnect   = true;
    bool        m_restoringSession = false;
    bool        m_retryingPendingLogin = false;
    int         m_reconnectAttempt = 0;
    static constexpr int MAX_RECONNECT = 10;

    int         m_userId          = 0;
    QString     m_username;
    QString     m_sessionPassword;
    QString     m_pendingLoginUsername;
    QString     m_pendingLoginPassword;
    QString     m_pendingNewPassword;
};
