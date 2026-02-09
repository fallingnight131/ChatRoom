#pragma once

#include <QObject>
#include <QTcpSocket>
#include <QJsonObject>
#include <QTimer>

/// 客户端会话 —— 每个连接的客户端对应一个实例，运行在独立线程中
class ClientSession : public QObject {
    Q_OBJECT
public:
    explicit ClientSession(qintptr socketDescriptor, QObject *parent = nullptr);
    ~ClientSession() override;

    int     userId() const   { return m_userId; }
    QString username() const { return m_username; }
    bool    isAuthenticated() const { return m_authenticated; }

    void setAuthenticated(int userId, const QString &username);
    void disconnectFromServer();

public slots:
    void init();
    void sendMessage(const QJsonObject &msg);

signals:
    void authenticated(ClientSession *session);
    void disconnected(ClientSession *session);
    void messageReceived(ClientSession *session, const QJsonObject &msg);

private slots:
    void onReadyRead();
    void onDisconnected();
    void onHeartbeatTimeout();

private:
    void processBuffer();

    qintptr     m_socketDescriptor;
    QTcpSocket *m_socket        = nullptr;
    QTimer     *m_heartbeatTimer = nullptr;
    QByteArray  m_buffer;

    int         m_userId        = 0;
    QString     m_username;
    bool        m_authenticated = false;
};
