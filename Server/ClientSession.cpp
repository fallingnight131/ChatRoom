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
        m_socket->setReadBufferSize(static_cast<qint64>(Protocol::MAX_JSON_MESSAGE_BYTES) + 4);
        connect(m_socket, &QTcpSocket::readyRead,    this, &ClientSession::onTcpReadyRead);
        connect(m_socket, &QTcpSocket::disconnected,  this, &ClientSession::onDisconnected);
        setupHeartbeat();
        qDebug() << "[Session/TCP] 初始化完成，来源:" << m_socket->peerAddress().toString();
    } else {
        // WebSocket: 信号连接在工作线程中进行，避免跨线程 QSocketNotifier 问题
        if (m_webSocket) {
            m_webSocket->setParent(this);
            m_webSocket->setMaxAllowedIncomingFrameSize(Protocol::MAX_JSON_MESSAGE_BYTES);
            m_webSocket->setMaxAllowedIncomingMessageSize(Protocol::MAX_JSON_MESSAGE_BYTES);
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
        if (packet.size() - 4 > Protocol::MAX_JSON_MESSAGE_BYTES
            || !ensureOutboundCapacity(packet.size()))
            return;
        m_socket->write(packet);
    } else {
        if (!m_webSocket || !m_webSocket->isValid())
            return;
        QByteArray json = QJsonDocument(msg).toJson(QJsonDocument::Compact);
        if (json.size() > Protocol::MAX_JSON_MESSAGE_BYTES
            || !ensureOutboundCapacity(json.size()))
            return;
        m_webSocket->sendTextMessage(QString::fromUtf8(json));
    }
}

// ==================== TCP 数据接收 ====================

void ClientSession::onTcpReadyRead() {
    m_buffer.append(m_socket->readAll());
    if (m_buffer.size() > static_cast<int>(Protocol::MAX_JSON_MESSAGE_BYTES) + 4) {
        rejectConnection(QStringLiteral("tcp-buffer-limit"));
        return;
    }
    processBuffer();
    if (m_heartbeatTimer)
        m_heartbeatTimer->start();
}

void ClientSession::processBuffer() {
    while (true) {
        QJsonObject msg;
        const Protocol::FrameParseResult result = Protocol::inspectFrame(m_buffer, msg);
        if (result == Protocol::FrameParseResult::Incomplete) return;
        if (result == Protocol::FrameParseResult::Oversized) {
            rejectConnection(QStringLiteral("tcp-frame-oversized"));
            return;
        }
        if (result == Protocol::FrameParseResult::Malformed) {
            ++m_malformedMessages;
            if (m_malformedMessages >= Protocol::MAX_MALFORMED_MESSAGES) {
                rejectConnection(QStringLiteral("tcp-json-malformed"));
                return;
            }
            continue;
        }
        if (!hasValidEnvelope(msg)) {
            if (m_malformedMessages >= Protocol::MAX_MALFORMED_MESSAGES) return;
            continue;
        }
        if (!allowInboundRate()) return;
        emit messageReceived(this, msg);
    }
}

// ==================== WebSocket 数据接收 ====================

void ClientSession::onWsTextReceived(const QString &text) {
    if (m_heartbeatTimer)
        m_heartbeatTimer->start();

    const QByteArray json = text.toUtf8();
    if (json.size() > Protocol::MAX_JSON_MESSAGE_BYTES) {
        rejectConnection(QStringLiteral("ws-message-oversized"));
        return;
    }

    QJsonParseError err;
    QJsonDocument doc = QJsonDocument::fromJson(json, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) {
        ++m_malformedMessages;
        if (m_malformedMessages >= Protocol::MAX_MALFORMED_MESSAGES)
            rejectConnection(QStringLiteral("ws-json-malformed"));
        return;
    }
    const QJsonObject msg = doc.object();
    if (!hasValidEnvelope(msg) || !allowInboundRate()) return;
    emit messageReceived(this, msg);
}

bool ClientSession::hasValidEnvelope(const QJsonObject &msg) {
    if (msg["type"].toString().isEmpty() || !msg["data"].isObject()) {
        ++m_malformedMessages;
        if (m_malformedMessages >= Protocol::MAX_MALFORMED_MESSAGES)
            rejectConnection(QStringLiteral("envelope-malformed"));
        return false;
    }
    return true;
}

bool ClientSession::allowInboundRate() {
    if (!m_rateWindow.isValid()) {
        m_rateWindow.start();
        m_messagesInWindow = 0;
    } else if (m_rateWindow.elapsed() >= 1000) {
        m_rateWindow.restart();
        m_messagesInWindow = 0;
    }
    ++m_messagesInWindow;
    if (m_messagesInWindow > Protocol::MAX_MESSAGES_PER_SECOND) {
        rejectConnection(QStringLiteral("inbound-rate-limit"));
        return false;
    }
    return true;
}

bool ClientSession::ensureOutboundCapacity(qint64 messageBytes) {
    const qint64 pending = m_transport == Tcp
        ? (m_socket ? m_socket->bytesToWrite() : 0)
        : (m_webSocket ? m_webSocket->bytesToWrite() : 0);
    if (messageBytes <= Protocol::MAX_PENDING_WRITE_BYTES - pending) return true;

    rejectConnection(QStringLiteral("slow-consumer"));
    return false;
}

void ClientSession::rejectConnection(const QString &category) {
    qWarning().noquote() << QStringLiteral("[Protocol] disconnect category=%1 userId=%2 transport=%3")
                                .arg(category)
                                .arg(m_userId)
                                .arg(m_transport == Tcp ? QStringLiteral("tcp")
                                                       : QStringLiteral("websocket"));
    m_buffer.clear();
    disconnectFromServer();
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
