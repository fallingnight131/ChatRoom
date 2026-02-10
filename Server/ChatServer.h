#pragma once

#include <QTcpServer>
#include <QMap>
#include <QSet>
#include <QMutex>
#include <QJsonObject>

class ClientSession;
class DatabaseManager;
class RoomManager;

/// 聊天服务器 —— 管理所有客户端连接和消息路由
class ChatServer : public QTcpServer {
    Q_OBJECT
public:
    explicit ChatServer(QObject *parent = nullptr);
    ~ChatServer() override;

    bool startServer(quint16 port);
    void stopServer();

    DatabaseManager *database() const { return m_db; }
    RoomManager     *roomManager() const { return m_roomMgr; }

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

private:
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
    void handleRecall(ClientSession *session, const QJsonObject &data);
    void handleSetAdmin(ClientSession *session, const QJsonObject &data);
    void handleDeleteMessages(ClientSession *session, const QJsonObject &data);

    DatabaseManager *m_db       = nullptr;
    RoomManager     *m_roomMgr  = nullptr;

    mutable QMutex m_mutex;
    QMap<QString, ClientSession*> m_sessions;  // username -> session
};
