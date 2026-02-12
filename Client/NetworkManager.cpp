#include "NetworkManager.h"
#include "Protocol.h"

#include <QSslSocket>
#include <QJsonArray>
#include <QJsonDocument>
#include <QDebug>

NetworkManager *NetworkManager::s_instance = nullptr;

NetworkManager *NetworkManager::instance() {
    if (!s_instance)
        s_instance = new NetworkManager();
    return s_instance;
}

NetworkManager::NetworkManager(QObject *parent)
    : QObject(parent)
{
    m_heartbeatTimer = new QTimer(this);
    m_heartbeatTimer->setInterval(Protocol::HEARTBEAT_INTERVAL_MS);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &NetworkManager::onHeartbeat);

    m_reconnectTimer = new QTimer(this);
    m_reconnectTimer->setInterval(Protocol::RECONNECT_INTERVAL_MS);
    m_reconnectTimer->setSingleShot(true);
    connect(m_reconnectTimer, &QTimer::timeout, this, &NetworkManager::tryReconnect);
}

NetworkManager::~NetworkManager() {
    disconnectFromServer();
    s_instance = nullptr;
}

void NetworkManager::connectToServer(const QString &host, quint16 port, bool useSsl) {
    m_host   = host;
    m_port   = port;
    m_useSsl = useSsl;
    m_reconnectAttempt = 0;

    if (m_socket) {
        m_socket->disconnect();
        m_socket->deleteLater();
    }

    if (useSsl) {
        auto *ssl = new QSslSocket(this);
        ssl->setPeerVerifyMode(QSslSocket::VerifyNone); // 开发阶段
        m_socket = ssl;
    } else {
        m_socket = new QTcpSocket(this);
    }

    connect(m_socket, &QTcpSocket::connected,    this, &NetworkManager::onConnected);
    connect(m_socket, &QTcpSocket::disconnected,  this, &NetworkManager::onDisconnected);
    connect(m_socket, &QTcpSocket::readyRead,     this, &NetworkManager::onReadyRead);
    connect(m_socket, QOverload<QAbstractSocket::SocketError>::of(&QAbstractSocket::errorOccurred),
            this, &NetworkManager::onError);

    if (useSsl) {
        static_cast<QSslSocket*>(m_socket)->connectToHostEncrypted(host, port);
    } else {
        m_socket->connectToHost(host, port);
    }
}

void NetworkManager::disconnectFromServer() {
    m_autoReconnect = false;
    m_heartbeatTimer->stop();
    m_reconnectTimer->stop();

    if (m_socket) {
        m_socket->disconnect();
        m_socket->close();
        m_socket->deleteLater();
        m_socket = nullptr;
    }
    m_buffer.clear();
}

bool NetworkManager::isConnected() const {
    return m_socket && m_socket->state() == QAbstractSocket::ConnectedState;
}

void NetworkManager::sendMessage(const QJsonObject &msg) {
    if (!isConnected()) return;
    m_socket->write(Protocol::pack(msg));
    m_socket->flush();
}

void NetworkManager::setCredentials(int userId, const QString &username) {
    m_userId   = userId;
    m_username = username;
}

// ==================== 连接事件 ====================

void NetworkManager::onConnected() {
    qInfo() << "[Net] 已连接到服务器";
    m_reconnectAttempt = 0;
    m_autoReconnect    = true;
    m_heartbeatTimer->start();
    m_buffer.clear();
    emit connected();
}

void NetworkManager::onDisconnected() {
    qInfo() << "[Net] 与服务器断开";
    m_heartbeatTimer->stop();
    emit disconnected();

    if (m_autoReconnect && m_reconnectAttempt < MAX_RECONNECT)
        m_reconnectTimer->start();
}

void NetworkManager::onError(QAbstractSocket::SocketError err) {
    Q_UNUSED(err)
    if (m_socket)
        emit connectionError(m_socket->errorString());
}

void NetworkManager::onReadyRead() {
    m_buffer.append(m_socket->readAll());

    QJsonObject msg;
    while (Protocol::unpack(m_buffer, msg)) {
        processMessage(msg);
    }
}

void NetworkManager::onHeartbeat() {
    sendMessage(Protocol::makeHeartbeat());
}

void NetworkManager::tryReconnect() {
    m_reconnectAttempt++;
    emit reconnecting(m_reconnectAttempt);
    qInfo() << "[Net] 尝试重连 #" << m_reconnectAttempt;
    connectToServer(m_host, m_port, m_useSsl);
}

// ==================== 消息分发 ====================

void NetworkManager::processMessage(const QJsonObject &msg) {
    QString type = msg["type"].toString();
    QJsonObject data = msg["data"].toObject();

    if (type == Protocol::MsgType::HEARTBEAT_ACK) {
        // 心跳确认，无需处理
        return;
    }

    if (type == Protocol::MsgType::LOGIN_RSP) {
        bool ok = data["success"].toBool();
        if (ok) {
            m_userId   = data["userId"].toInt();
            m_username = data["username"].toString();
        }
        emit loginResponse(ok,
                           data["error"].toString(),
                           data["userId"].toInt(),
                           data["username"].toString());
    }
    else if (type == Protocol::MsgType::REGISTER_RSP) {
        emit registerResponse(data["success"].toBool(), data["error"].toString());
    }
    else if (type == Protocol::MsgType::CHAT_MSG) {
        emit chatMessageReceived(msg);
    }
    else if (type == Protocol::MsgType::SYSTEM_MSG) {
        emit systemMessageReceived(msg);
    }
    else if (type == Protocol::MsgType::CREATE_ROOM_RSP) {
        emit roomCreated(data["success"].toBool(),
                         data["roomId"].toInt(),
                         data["roomName"].toString(),
                         data["error"].toString());
        if (data["success"].toBool() && data.contains("isAdmin")) {
            emit adminStatusChanged(data["roomId"].toInt(), data["isAdmin"].toBool());
        }
    }
    else if (type == Protocol::MsgType::JOIN_ROOM_RSP) {
        bool success = data["success"].toBool();
        if (!success && data["needPassword"].toBool()) {
            emit joinRoomNeedPassword(data["roomId"].toInt());
        } else {
            emit roomJoined(success,
                            data["roomId"].toInt(),
                            data["roomName"].toString(),
                            data["error"].toString(),
                            data["newJoin"].toBool());
            // 加入成功时也通知管理员状态
            if (success && data.contains("isAdmin")) {
                emit adminStatusChanged(data["roomId"].toInt(), data["isAdmin"].toBool());
            }
        }
    }
    else if (type == Protocol::MsgType::ROOM_LIST_RSP) {
        emit roomListReceived(data["rooms"].toArray());
    }
    else if (type == Protocol::MsgType::USER_LIST_RSP) {
        emit userListReceived(data["roomId"].toInt(), data["users"].toArray());
    }
    else if (type == Protocol::MsgType::USER_JOINED) {
        emit userJoined(data["roomId"].toInt(), data["username"].toString());
    }
    else if (type == Protocol::MsgType::USER_LEFT) {
        emit userLeft(data["roomId"].toInt(), data["username"].toString());
    }
    else if (type == Protocol::MsgType::USER_ONLINE) {
        emit userOnline(data["roomId"].toInt(), data["username"].toString());
    }
    else if (type == Protocol::MsgType::USER_OFFLINE) {
        emit userOffline(data["roomId"].toInt(), data["username"].toString());
    }
    else if (type == Protocol::MsgType::LEAVE_ROOM_RSP) {
        emit leaveRoomResponse(data["success"].toBool(), data["roomId"].toInt());
    }
    else if (type == Protocol::MsgType::HISTORY_RSP) {
        emit historyReceived(data["roomId"].toInt(), data["messages"].toArray());
    }
    else if (type == Protocol::MsgType::FILE_NOTIFY) {
        emit fileNotify(data);
    }
    else if (type == Protocol::MsgType::FILE_DOWNLOAD_RSP) {
        emit fileDownloadReady(data);
    }
    else if (type == Protocol::MsgType::FILE_UPLOAD_START_RSP) {
        emit uploadStartResponse(data);
    }
    else if (type == Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP) {
        emit uploadChunkResponse(data);
    }
    else if (type == Protocol::MsgType::FILE_DOWNLOAD_CHUNK_RSP) {
        emit downloadChunkResponse(data);
    }
    else if (type == Protocol::MsgType::RECALL_RSP) {
        emit recallResponse(data["success"].toBool(),
                            data["messageId"].toInt(),
                            data["error"].toString());
    }
    else if (type == Protocol::MsgType::RECALL_NOTIFY) {
        emit recallNotify(data["messageId"].toInt(),
                          data["roomId"].toInt(),
                          data["username"].toString());
    }
    else if (type == Protocol::MsgType::FORCE_OFFLINE) {
        emit forceOffline(data["reason"].toString());
        // 禁止自动重连
        m_autoReconnect = false;
        if (m_socket) {
            m_socket->disconnect();
            m_socket->close();
        }
    }
    else if (type == Protocol::MsgType::ADMIN_STATUS) {
        emit adminStatusChanged(data["roomId"].toInt(), data["isAdmin"].toBool());
    }
    else if (type == Protocol::MsgType::SET_ADMIN_RSP) {
        emit setAdminResponse(data["success"].toBool(),
                              data["roomId"].toInt(),
                              data["username"].toString(),
                              data["error"].toString());
    }
    else if (type == Protocol::MsgType::DELETE_MSGS_RSP) {
        emit deleteMsgsResponse(data["success"].toBool(),
                                data["roomId"].toInt(),
                                data["deletedCount"].toInt(),
                                data["mode"].toString(),
                                data["error"].toString());
    }
    else if (type == Protocol::MsgType::DELETE_MSGS_NOTIFY) {
        emit deleteMsgsNotify(data["roomId"].toInt(),
                              data["mode"].toString(),
                              data["messageIds"].toArray());
    }
    else if (type == Protocol::MsgType::AVATAR_UPLOAD_RSP) {
        emit avatarUploadResponse(data["success"].toBool(), data["error"].toString());
    }
    else if (type == Protocol::MsgType::AVATAR_GET_RSP) {
        QByteArray avatarData;
        if (data["success"].toBool())
            avatarData = QByteArray::fromBase64(data["avatarData"].toString().toLatin1());
        emit avatarGetResponse(data["username"].toString(), avatarData);
    }
    else if (type == Protocol::MsgType::AVATAR_UPDATE_NOTIFY) {
        QByteArray avatarData = QByteArray::fromBase64(data["avatarData"].toString().toLatin1());
        emit avatarUpdateNotify(data["username"].toString(), avatarData);
    }
    else if (type == Protocol::MsgType::ROOM_SETTINGS_RSP) {
        emit roomSettingsResponse(data["roomId"].toInt(),
                                  data["success"].toBool(),
                                  static_cast<qint64>(data["maxFileSize"].toDouble()),
                                  data["error"].toString());
    }
    else if (type == Protocol::MsgType::ROOM_SETTINGS_NOTIFY) {
        emit roomSettingsNotify(data["roomId"].toInt(),
                                static_cast<qint64>(data["maxFileSize"].toDouble()));
    }
    else if (type == Protocol::MsgType::DELETE_ROOM_RSP) {
        emit deleteRoomResponse(data["success"].toBool(),
                                data["roomId"].toInt(),
                                data["roomName"].toString(),
                                data["error"].toString());
    }
    else if (type == Protocol::MsgType::DELETE_ROOM_NOTIFY) {
        emit deleteRoomNotify(data["roomId"].toInt(),
                              data["roomName"].toString(),
                              data["operator"].toString());
    }
    else if (type == Protocol::MsgType::RENAME_ROOM_RSP) {
        emit renameRoomResponse(data["success"].toBool(),
                                data["roomId"].toInt(),
                                data["newName"].toString(),
                                data["error"].toString());
    }
    else if (type == Protocol::MsgType::RENAME_ROOM_NOTIFY) {
        emit renameRoomNotify(data["roomId"].toInt(),
                              data["newName"].toString());
    }
    else if (type == Protocol::MsgType::SET_ROOM_PASSWORD_RSP) {
        emit setRoomPasswordResponse(data["success"].toBool(),
                                     data["roomId"].toInt(),
                                     data["hasPassword"].toBool(),
                                     data["error"].toString());
    }
    else if (type == Protocol::MsgType::GET_ROOM_PASSWORD_RSP) {
        emit getRoomPasswordResponse(data["success"].toBool(),
                                     data["roomId"].toInt(),
                                     data["password"].toString(),
                                     data["hasPassword"].toBool(),
                                     data["error"].toString());
    }
    else if (type == Protocol::MsgType::KICK_USER_RSP) {
        emit kickUserResponse(data["success"].toBool(),
                              data["roomId"].toInt(),
                              data["username"].toString(),
                              data["error"].toString());
    }
    else if (type == Protocol::MsgType::KICK_USER_NOTIFY) {
        emit kickedFromRoom(data["roomId"].toInt(),
                            data["roomName"].toString(),
                            data["operator"].toString());
    }
}
