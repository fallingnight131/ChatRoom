#include "ChatServer.h"
#include "ClientSession.h"
#include "DatabaseManager.h"
#include "RoomManager.h"
#include "Protocol.h"
#include "Message.h"

#include <QThread>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QDebug>
#include <QFile>
#include <QDir>

ChatServer::ChatServer(QObject *parent)
    : QTcpServer(parent)
{
    m_db      = new DatabaseManager(this);
    m_roomMgr = new RoomManager(this);
}

ChatServer::~ChatServer() {
    stopServer();
}

bool ChatServer::startServer(quint16 port) {
    // 初始化数据库
    if (!m_db->initialize()) {
        qCritical() << "[Server] 数据库初始化失败";
        return false;
    }
    // 初始化房间管理器（从数据库加载房间列表）
    m_roomMgr->loadRooms(m_db);

    if (!listen(QHostAddress::Any, port)) {
        qCritical() << "[Server] 监听端口失败:" << port << errorString();
        return false;
    }
    qInfo() << "[Server] 服务器已启动，监听端口:" << port;
    return true;
}

void ChatServer::stopServer() {
    close();
    QMutexLocker locker(&m_mutex);
    for (auto *s : qAsConst(m_sessions))
        s->disconnectFromServer();
    m_sessions.clear();
}

// ==================== 新连接 ====================

void ChatServer::incomingConnection(qintptr socketDescriptor) {
    qInfo() << "[Server] 新连接:" << socketDescriptor;

    QThread *thread = new QThread(this);
    ClientSession *session = new ClientSession(socketDescriptor);
    session->moveToThread(thread);

    connect(thread,  &QThread::started,  session, &ClientSession::init);
    connect(session, &ClientSession::authenticated,  this, &ChatServer::onClientAuthenticated);
    connect(session, &ClientSession::disconnected,   this, &ChatServer::onClientDisconnected);
    connect(session, &ClientSession::messageReceived,this, &ChatServer::onClientMessage);

    connect(session, &ClientSession::destroyed, thread, &QThread::quit);
    connect(thread,  &QThread::finished,        thread, &QThread::deleteLater);

    thread->start();
}

// ==================== 会话事件 ====================

void ChatServer::onClientAuthenticated(ClientSession *session) {
    QMutexLocker locker(&m_mutex);
    // 踢掉旧连接
    if (m_sessions.contains(session->username())) {
        ClientSession *old = m_sessions[session->username()];
        old->disconnectFromServer();
    }
    m_sessions[session->username()] = session;
    qInfo() << "[Server] 用户认证成功:" << session->username();
}

void ChatServer::onClientDisconnected(ClientSession *session) {
    QString username = session->username();
    {
        QMutexLocker locker(&m_mutex);
        if (m_sessions.value(username) == session)
            m_sessions.remove(username);
    }

    // 通知该用户所在的所有房间
    if (!username.isEmpty()) {
        QList<int> rooms = m_roomMgr->userRooms(session->userId());
        for (int roomId : rooms) {
            m_roomMgr->removeUserFromRoom(roomId, session->userId());
            QJsonObject data;
            data["roomId"]   = roomId;
            data["username"] = username;
            broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LEFT, data));
        }
    }

    qInfo() << "[Server] 用户断开:" << username;
    session->deleteLater();
}

void ChatServer::onClientMessage(ClientSession *session, const QJsonObject &msg) {
    QString type = msg["type"].toString();

    if (type == Protocol::MsgType::LOGIN_REQ) {
        handleLogin(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::REGISTER_REQ) {
        handleRegister(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::CHAT_MSG) {
        handleChatMessage(session, msg);
    } else if (type == Protocol::MsgType::CREATE_ROOM_REQ) {
        handleCreateRoom(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::JOIN_ROOM_REQ) {
        handleJoinRoom(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::LEAVE_ROOM) {
        handleLeaveRoom(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_LIST_REQ) {
        handleRoomList(session);
    } else if (type == Protocol::MsgType::USER_LIST_REQ) {
        handleUserList(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::HISTORY_REQ) {
        handleHistory(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FILE_SEND) {
        handleFileSend(session, msg);
    } else if (type == Protocol::MsgType::FILE_DOWNLOAD_REQ) {
        handleFileDownload(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::RECALL_REQ) {
        handleRecall(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::HEARTBEAT) {
        session->sendMessage(Protocol::makeHeartbeatAck());
    }
}

// ==================== 认证处理 ====================

void ChatServer::handleLogin(ClientSession *session, const QJsonObject &data) {
    QString username = data["username"].toString();
    QString password = data["password"].toString();

    int userId = m_db->authenticateUser(username, password);

    QJsonObject rspData;
    if (userId > 0) {
        session->setAuthenticated(userId, username);
        rspData["success"]  = true;
        rspData["userId"]   = userId;
        rspData["username"] = username;
        emit session->authenticated(session);
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户名或密码错误");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LOGIN_RSP, rspData));
}

void ChatServer::handleRegister(ClientSession *session, const QJsonObject &data) {
    QString username = data["username"].toString();
    QString password = data["password"].toString();

    QJsonObject rspData;
    if (username.length() < 2 || password.length() < 4) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户名至少2字符，密码至少4字符");
    } else {
        int userId = m_db->registerUser(username, password);
        if (userId > 0) {
            rspData["success"]  = true;
            rspData["userId"]   = userId;
            rspData["username"] = username;
        } else {
            rspData["success"] = false;
            rspData["error"]   = QStringLiteral("用户名已存在");
        }
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::REGISTER_RSP, rspData));
}

// ==================== 聊天消息 ====================

void ChatServer::handleChatMessage(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    QJsonObject data = msg["data"].toObject();
    int roomId = data["roomId"].toInt();

    // 存入数据库
    int msgId = m_db->saveMessage(roomId, session->userId(), data["content"].toString(),
                                   data["contentType"].toString());

    // 补全消息信息
    QJsonObject fullData = data;
    fullData["id"]     = msgId;
    fullData["sender"] = session->username();
    QJsonObject fullMsg = Protocol::makeMessage(Protocol::MsgType::CHAT_MSG, fullData);

    broadcastToRoom(roomId, fullMsg);
}

// ==================== 房间管理 ====================

void ChatServer::handleCreateRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString roomName = data["roomName"].toString();
    int roomId = m_db->createRoom(roomName, session->userId());

    QJsonObject rspData;
    if (roomId > 0) {
        m_roomMgr->addRoom(roomId, roomName, session->userId());
        m_roomMgr->addUserToRoom(roomId, session->userId(), session->username());
        m_db->joinRoom(roomId, session->userId());
        rspData["success"]  = true;
        rspData["roomId"]   = roomId;
        rspData["roomName"] = roomName;
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("创建房间失败");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CREATE_ROOM_RSP, rspData));
}

void ChatServer::handleJoinRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QJsonObject rspData;

    if (m_roomMgr->roomExists(roomId)) {
        m_roomMgr->addUserToRoom(roomId, session->userId(), session->username());
        m_db->joinRoom(roomId, session->userId());

        rspData["success"]  = true;
        rspData["roomId"]   = roomId;
        rspData["roomName"] = m_roomMgr->roomName(roomId);

        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));

        // 通知房间其他成员
        QJsonObject notifyData;
        notifyData["roomId"]   = roomId;
        notifyData["username"] = session->username();
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_JOINED, notifyData), session);
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("房间不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));
    }
}

void ChatServer::handleLeaveRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    m_roomMgr->removeUserFromRoom(roomId, session->userId());

    QJsonObject notifyData;
    notifyData["roomId"]   = roomId;
    notifyData["username"] = session->username();
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LEFT, notifyData));
}

void ChatServer::handleRoomList(ClientSession *session) {
    QJsonArray roomArr;
    auto rooms = m_roomMgr->allRooms();
    for (auto it = rooms.constBegin(); it != rooms.constEnd(); ++it) {
        QJsonObject r;
        r["roomId"]   = it.key();
        r["roomName"] = it.value();
        roomArr.append(r);
    }
    QJsonObject rspData;
    rspData["rooms"] = roomArr;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_LIST_RSP, rspData));
}

void ChatServer::handleUserList(ClientSession *session, const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    QStringList users = onlineUsersInRoom(roomId);

    QJsonArray userArr;
    for (const QString &u : users)
        userArr.append(u);

    QJsonObject rspData;
    rspData["roomId"] = roomId;
    rspData["users"]  = userArr;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_LIST_RSP, rspData));
}

void ChatServer::handleHistory(ClientSession *session, const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    int count  = data["count"].toInt(50);
    qint64 before = static_cast<qint64>(data["before"].toDouble(0));

    QJsonArray messages = m_db->getMessageHistory(roomId, count, before);

    QJsonObject rspData;
    rspData["roomId"]   = roomId;
    rspData["messages"] = messages;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::HISTORY_RSP, rspData));
}

// ==================== 文件传输 ====================

void ChatServer::handleFileSend(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    QJsonObject data = msg["data"].toObject();
    int roomId        = data["roomId"].toInt();
    QString fileName  = data["fileName"].toString();
    qint64 fileSize   = static_cast<qint64>(data["fileSize"].toDouble());
    QString fileData  = data["fileData"].toString(); // base64

    // 保存文件到服务器磁盘
    QDir dir("server_files");
    if (!dir.exists()) dir.mkpath(".");

    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = dir.filePath(safeName);

    QFile file(filePath);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QByteArray::fromBase64(fileData.toUtf8()));
        file.close();
    }

    // 保存文件信息到数据库
    int fileId = m_db->saveFile(roomId, session->userId(), fileName, filePath, fileSize);

    // 保存消息记录
    int msgId = m_db->saveMessage(roomId, session->userId(), fileName, "file", fileName, fileSize, fileId);

    // 通知房间所有成员有新文件
    QJsonObject notifyData;
    notifyData["id"]          = msgId;
    notifyData["roomId"]      = roomId;
    notifyData["sender"]      = session->username();
    notifyData["fileName"]    = fileName;
    notifyData["fileSize"]    = static_cast<double>(fileSize);
    notifyData["fileId"]      = fileId;
    notifyData["contentType"] = QStringLiteral("file");
    notifyData["content"]     = fileName;

    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));
}

void ChatServer::handleFileDownload(ClientSession *session, const QJsonObject &data) {
    int fileId = data["fileId"].toInt();

    QString filePath = m_db->getFilePath(fileId);
    QJsonObject rspData;

    if (!filePath.isEmpty()) {
        QFile file(filePath);
        if (file.open(QIODevice::ReadOnly)) {
            QByteArray content = file.readAll();
            file.close();
            rspData["success"]  = true;
            rspData["fileId"]   = fileId;
            rspData["fileName"] = data["fileName"].toString();
            rspData["fileData"] = QString::fromUtf8(content.toBase64());
        } else {
            rspData["success"] = false;
            rspData["error"]   = QStringLiteral("文件不存在");
        }
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件记录不存在");
    }

    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_RSP, rspData));
}

// ==================== 消息撤回 ====================

void ChatServer::handleRecall(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int messageId = data["messageId"].toInt();
    int roomId    = data["roomId"].toInt();

    QJsonObject rspData;
    rspData["messageId"] = messageId;
    rspData["roomId"]    = roomId;

    // 验证消息所有权和时间限制
    bool ok = m_db->recallMessage(messageId, session->userId(), Protocol::RECALL_TIME_LIMIT_SEC);
    if (ok) {
        rspData["success"] = true;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RECALL_RSP, rspData));

        // 通知房间所有人
        QJsonObject notifyData;
        notifyData["messageId"] = messageId;
        notifyData["roomId"]    = roomId;
        notifyData["username"]  = session->username();
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::RECALL_NOTIFY, notifyData));
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("无法撤回（超时或非本人消息）");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RECALL_RSP, rspData));
    }
}

// ==================== 广播/发送 ====================

void ChatServer::broadcastToRoom(int roomId, const QJsonObject &msg, ClientSession *exclude) {
    QStringList users = m_roomMgr->usersInRoom(roomId);
    QMutexLocker locker(&m_mutex);
    for (const QString &username : users) {
        if (m_sessions.contains(username)) {
            ClientSession *s = m_sessions[username];
            if (s != exclude)
                QMetaObject::invokeMethod(s, "sendMessage", Qt::QueuedConnection,
                                          Q_ARG(QJsonObject, msg));
        }
    }
}

void ChatServer::sendToUser(const QString &username, const QJsonObject &msg) {
    QMutexLocker locker(&m_mutex);
    if (m_sessions.contains(username)) {
        QMetaObject::invokeMethod(m_sessions[username], "sendMessage", Qt::QueuedConnection,
                                  Q_ARG(QJsonObject, msg));
    }
}

QStringList ChatServer::onlineUsersInRoom(int roomId) const {
    QStringList roomUsers = m_roomMgr->usersInRoom(roomId);
    QStringList online;
    QMutexLocker locker(&m_mutex);
    for (const QString &u : roomUsers) {
        if (m_sessions.contains(u))
            online.append(u);
    }
    return online;
}
