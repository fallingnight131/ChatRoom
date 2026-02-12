#pragma once

#include <QObject>
#include <QTcpSocket>
#include <QSslSocket>
#include <QJsonObject>
#include <QTimer>

/// 网络管理器 —— 单例，管理与服务器的 TCP 连接
/// 观察者模式：通过信号通知各 UI 组件
class NetworkManager : public QObject {
    Q_OBJECT
public:
    static NetworkManager *instance();

    void connectToServer(const QString &host, quint16 port, bool useSsl = false);
    void disconnectFromServer();
    void sendMessage(const QJsonObject &msg);

    bool isConnected() const;
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
    void loginResponse(bool success, const QString &error, int userId, const QString &username);
    void registerResponse(bool success, const QString &error);

    // 聊天消息
    void chatMessageReceived(const QJsonObject &msg);
    void systemMessageReceived(const QJsonObject &msg);

    // 房间
    void roomCreated(bool success, int roomId, const QString &roomName, const QString &error);
    void roomJoined(bool success, int roomId, const QString &roomName, const QString &error, bool newJoin = false);
    void roomListReceived(const QJsonArray &rooms);
    void userListReceived(int roomId, const QJsonArray &users);
    void userJoined(int roomId, const QString &username);
    void userLeft(int roomId, const QString &username);
    void userOnline(int roomId, const QString &username);
    void userOffline(int roomId, const QString &username);
    void leaveRoomResponse(bool success, int roomId);

    // 历史消息
    void historyReceived(int roomId, const QJsonArray &messages);

    // 文件
    void fileNotify(const QJsonObject &data);
    void fileDownloadReady(const QJsonObject &data);

    // 大文件分块传输
    void uploadStartResponse(const QJsonObject &data);
    void uploadChunkResponse(const QJsonObject &data);
    void downloadChunkResponse(const QJsonObject &data);

    // 撤回
    void recallResponse(bool success, int messageId, const QString &error);
    void recallNotify(int messageId, int roomId, const QString &username);

    // 强制下线
    void forceOffline(const QString &reason);

    // 管理员
    void adminStatusChanged(int roomId, bool isAdmin);
    void setAdminResponse(bool success, int roomId, const QString &username, const QString &error);
    void deleteMsgsResponse(bool success, int roomId, int deletedCount, const QString &mode, const QJsonArray &deletedFileIds, const QString &error);
    void deleteMsgsNotify(int roomId, const QString &mode, const QJsonArray &messageIds, const QJsonArray &deletedFileIds);

    // 头像
    void avatarUploadResponse(bool success, const QString &error);
    void avatarGetResponse(const QString &username, const QByteArray &avatarData);
    void avatarUpdateNotify(const QString &username, const QByteArray &avatarData);

    // 房间设置
    void roomSettingsResponse(int roomId, bool success, qint64 maxFileSize, const QString &error);
    void roomSettingsNotify(int roomId, qint64 maxFileSize);

    // 删除聊天室
    void deleteRoomResponse(bool success, int roomId, const QString &roomName, const QString &error);
    void deleteRoomNotify(int roomId, const QString &roomName, const QString &operatorName);

    // 重命名聊天室
    void renameRoomResponse(bool success, int roomId, const QString &newName, const QString &error);
    void renameRoomNotify(int roomId, const QString &newName);
    void setRoomPasswordResponse(bool success, int roomId, bool hasPassword, const QString &error);
    void getRoomPasswordResponse(bool success, int roomId, const QString &password, bool hasPassword, const QString &error);
    void joinRoomNeedPassword(int roomId);
    void kickUserResponse(bool success, int roomId, const QString &username, const QString &error);
    void kickedFromRoom(int roomId, const QString &roomName, const QString &operatorName);

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

    static NetworkManager *s_instance;

    QTcpSocket *m_socket          = nullptr;
    QTimer     *m_heartbeatTimer  = nullptr;
    QTimer     *m_reconnectTimer  = nullptr;
    QByteArray  m_buffer;

    QString     m_host;
    quint16     m_port            = 0;
    bool        m_useSsl          = false;
    bool        m_autoReconnect   = true;
    int         m_reconnectAttempt = 0;
    static constexpr int MAX_RECONNECT = 10;

    int         m_userId          = 0;
    QString     m_username;
};
