#include "ClientSession.h"
#include "Protocol.h"

#include <QJsonDocument>
#include <QThread>
#include <QDebug>
#include <QWebSocket>

// ==================== TCP 构造 ====================

ClientSession::ClientSession(qintptr socketDescriptor, QObject *parent)
    : QObject(parent)
    , m_transport(Tcp)
    , m_socketDescriptor(socketDescriptor)
{
}

// ==================== WebSocket 构造 ====================

ClientSession::ClientSession(QWebSocket *ws, QObject *parent)
    : QObject(parent)
    , m_transport(WebSock)
    , m_webSocket(ws)
{
    // 仅保存指针，信号连接延迟到 init() 在工作线程中执行
}

ClientSession::~ClientSession() {
    if (m_socket) {
        m_socket->close();
        m_socket->deleteLater();
    }
    if (m_webSocket) {
        m_webSocket->close();
        m_webSocket->deleteLater();
    }
}

// ==================== 初始化（仅 TCP） ====================

void ClientSession::init() {
    if (m_transport == Tcp) {
        m_socket = new QTcpSocket(this);
        if (!m_socket->setSocketDescriptor(m_socketDescriptor)) {
            qWarning() << "[Session/TCP] 无法设置 socket descriptor";
            emit disconnected(this);
            return;
        }
        connect(m_socket, &QTcpSocket::readyRead,    this, &ClientSession::onTcpReadyRead);
        connect(m_socket, &QTcpSocket::disconnected,  this, &ClientSession::onDisconnected);
        setupHeartbeat();
        qDebug() << "[Session/TCP] 初始化完成，来源:" << m_socket->peerAddress().toString();
    } else {
        // WebSocket: 信号连接在工作线程中进行，避免跨线程 QSocketNotifier 问题
        if (m_webSocket) {
            m_webSocket->setParent(this);
            connect(m_webSocket, &QWebSocket::textMessageReceived,
                    this, &ClientSession::onWsTextReceived);
            connect(m_webSocket, &QWebSocket::disconnected,
                    this, &ClientSession::onDisconnected);
            setupHeartbeat();
            qDebug() << "[Session/WS] 初始化完成，来源:" << m_webSocket->peerAddress().toString();
        }
    }
}

void ClientSession::setupHeartbeat() {
    m_heartbeatTimer = new QTimer(this);
    m_heartbeatTimer->setInterval(Protocol::HEARTBEAT_TIMEOUT_MS);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &ClientSession::onHeartbeatTimeout);
    m_heartbeatTimer->start();
}

void ClientSession::setAuthenticated(int userId, const QString &username, const QString &displayName) {
    m_userId        = userId;
    m_username      = username;
    m_displayName   = displayName;
    m_authenticated = true;
}

void ClientSession::disconnectFromServer() {
    if (QThread::currentThread() != this->thread()) {
        QMetaObject::invokeMethod(this, "disconnectFromServer", Qt::QueuedConnection);
        return;
    }
    if (m_transport == Tcp) {
        if (m_socket && m_socket->state() == QAbstractSocket::ConnectedState)
            m_socket->disconnectFromHost();
    } else {
        if (m_webSocket)
            m_webSocket->close();
    }
}

// ==================== 发送消息（自动选择传输层） ====================

void ClientSession::sendMessage(const QJsonObject &msg) {
    if (QThread::currentThread() != this->thread()) {
        QMetaObject::invokeMethod(this, "sendMessage", Qt::QueuedConnection,
                                  Q_ARG(QJsonObject, msg));
        return;
    }

    if (m_transport == Tcp) {
        if (!m_socket || m_socket->state() != QAbstractSocket::ConnectedState)
            return;
        QByteArray packet = Protocol::pack(msg);
        m_socket->write(packet);
        m_socket->flush();
    } else {
        if (!m_webSocket || !m_webSocket->isValid())
            return;
        QByteArray json = QJsonDocument(msg).toJson(QJsonDocument::Compact);
        m_webSocket->sendTextMessage(QString::fromUtf8(json));
    }
}

// ==================== TCP 数据接收 ====================

void ClientSession::onTcpReadyRead() {
    m_buffer.append(m_socket->readAll());
    processBuffer();
    if (m_heartbeatTimer)
        m_heartbeatTimer->start();
}

void ClientSession::processBuffer() {
    QJsonObject msg;
    while (Protocol::unpack(m_buffer, msg)) {
        emit messageReceived(this, msg);
    }
}

// ==================== WebSocket 数据接收 ====================

void ClientSession::onWsTextReceived(const QString &text) {
    if (m_heartbeatTimer)
        m_heartbeatTimer->start();

    QJsonParseError err;
    QJsonDocument doc = QJsonDocument::fromJson(text.toUtf8(), &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) {
        qWarning() << "[Session/WS] JSON 解析失败:" << err.errorString();
        return;
    }
    emit messageReceived(this, doc.object());
}

// ==================== 公共事件 ====================

void ClientSession::onDisconnected() {
    qDebug() << "[Session] 断开:" << m_username
             << (m_transport == Tcp ? "(TCP)" : "(WS)");
    if (m_heartbeatTimer)
        m_heartbeatTimer->stop();
    emit disconnected(this);
}

void ClientSession::onHeartbeatTimeout() {
    qWarning() << "[Session] 心跳超时:" << m_username;
    if (m_transport == Tcp) {
        if (m_socket) m_socket->disconnectFromHost();
    } else {
        if (m_webSocket) m_webSocket->close();
    }
}
