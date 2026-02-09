#include "ClientSession.h"
#include "Protocol.h"

#include <QJsonDocument>
#include <QThread>
#include <QDebug>

ClientSession::ClientSession(qintptr socketDescriptor, QObject *parent)
    : QObject(parent)
    , m_socketDescriptor(socketDescriptor)
{
}

ClientSession::~ClientSession() {
    if (m_socket) {
        m_socket->close();
        m_socket->deleteLater();
    }
}

void ClientSession::init() {
    // 在所属线程中创建 Socket
    m_socket = new QTcpSocket(this);
    if (!m_socket->setSocketDescriptor(m_socketDescriptor)) {
        qWarning() << "[Session] 无法设置 socket descriptor";
        emit disconnected(this);
        return;
    }

    connect(m_socket, &QTcpSocket::readyRead,    this, &ClientSession::onReadyRead);
    connect(m_socket, &QTcpSocket::disconnected,  this, &ClientSession::onDisconnected);

    // 心跳检测定时器
    m_heartbeatTimer = new QTimer(this);
    m_heartbeatTimer->setInterval(Protocol::HEARTBEAT_TIMEOUT_MS);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &ClientSession::onHeartbeatTimeout);
    m_heartbeatTimer->start();

    qDebug() << "[Session] 初始化完成，来源:" << m_socket->peerAddress().toString();
}

void ClientSession::setAuthenticated(int userId, const QString &username) {
    m_userId        = userId;
    m_username      = username;
    m_authenticated = true;
}

void ClientSession::disconnectFromServer() {
    if (QThread::currentThread() != this->thread()) {
        QMetaObject::invokeMethod(this, "disconnectFromServer", Qt::QueuedConnection);
        return;
    }
    if (m_socket && m_socket->state() == QAbstractSocket::ConnectedState) {
        m_socket->disconnectFromHost();
    }
}

void ClientSession::sendMessage(const QJsonObject &msg) {
    if (QThread::currentThread() != this->thread()) {
        // 跨线程调用，转发到 session 所在线程执行
        QMetaObject::invokeMethod(this, "sendMessage", Qt::QueuedConnection,
                                  Q_ARG(QJsonObject, msg));
        return;
    }
    if (!m_socket || m_socket->state() != QAbstractSocket::ConnectedState)
        return;

    QByteArray packet = Protocol::pack(msg);
    m_socket->write(packet);
    m_socket->flush();
}

void ClientSession::onReadyRead() {
    m_buffer.append(m_socket->readAll());
    processBuffer();

    // 收到任何数据都重置心跳计时
    if (m_heartbeatTimer)
        m_heartbeatTimer->start();
}

void ClientSession::onDisconnected() {
    qDebug() << "[Session] 断开:" << m_username;
    if (m_heartbeatTimer)
        m_heartbeatTimer->stop();
    emit disconnected(this);
}

void ClientSession::onHeartbeatTimeout() {
    qWarning() << "[Session] 心跳超时:" << m_username;
    if (m_socket)
        m_socket->disconnectFromHost();
}

void ClientSession::processBuffer() {
    QJsonObject msg;
    while (Protocol::unpack(m_buffer, msg)) {
        emit messageReceived(this, msg);
    }
}
