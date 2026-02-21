#pragma once

#include <QObject>
#include <QTcpSocket>
#include <QJsonObject>
#include <QTimer>

class QWebSocket;

/// 客户端会话 —— 每个连接的客户端对应一个实例
/// 同时支持 TCP（Qt客户端）和 WebSocket（Web客户端）两种传输层
class ClientSession : public QObject {
    Q_OBJECT
public:
    enum Transport { Tcp, WebSock };

    /// TCP 连接（通过 socketDescriptor 初始化）
    explicit ClientSession(qintptr socketDescriptor, QObject *parent = nullptr);
    /// WebSocket 连接（已就绪的 QWebSocket，所有权转移给 Session）
    explicit ClientSession(QWebSocket *ws, QObject *parent = nullptr);
    ~ClientSession() override;

    Transport transport() const { return m_transport; }
    int     userId() const   { return m_userId; }
    QString username() const { return m_username; }
    QString displayName() const { return m_displayName; }
    bool    isAuthenticated() const { return m_authenticated; }

    void setAuthenticated(int userId, const QString &username, const QString &displayName);
    void setDisplayName(const QString &dn) { m_displayName = dn; }
    void setUsername(const QString &u) { m_username = u; }
    void setKicked(bool v) { m_kicked = v; }
    bool isKicked() const  { return m_kicked; }

public slots:
    void init();              // 仅 TCP 需要；WebSocket 在构造时已就绪
    void sendMessage(const QJsonObject &msg);
    void disconnectFromServer();

signals:
    void authenticated(ClientSession *session);
    void disconnected(ClientSession *session);
    void messageReceived(ClientSession *session, const QJsonObject &msg);

private slots:
    void onTcpReadyRead();          // TCP
    void onWsTextReceived(const QString &text); // WebSocket
    void onDisconnected();
    void onHeartbeatTimeout();

private:
    void processBuffer();           // TCP 帧解析
    void setupHeartbeat();
    void handleJsonMessage(const QJsonObject &msg);

    Transport    m_transport        = Tcp;
    qintptr      m_socketDescriptor = -1;
    QTcpSocket  *m_socket           = nullptr;
    QWebSocket  *m_webSocket        = nullptr;
    QTimer      *m_heartbeatTimer   = nullptr;
    QByteArray   m_buffer;

    int          m_userId           = 0;
    QString      m_username;
    QString      m_displayName;
    bool         m_authenticated    = false;
    bool         m_kicked           = false;
};
