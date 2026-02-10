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
    // 踢出已在 handleLogin 中处理，这里直接注册
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

    // 被踢出的 session 不清理房间（新的 session 会继承房间状态）
    if (!username.isEmpty() && !session->isKicked()) {
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
    } else if (type == Protocol::MsgType::FILE_UPLOAD_START) {
        handleFileUploadStart(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FILE_UPLOAD_CHUNK) {
        handleFileUploadChunk(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FILE_UPLOAD_END) {
        handleFileUploadEnd(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ) {
        handleFileDownloadChunk(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::RECALL_REQ) {
        handleRecall(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::SET_ADMIN_REQ) {
        handleSetAdmin(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::DELETE_MSGS_REQ) {
        handleDeleteMessages(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_SETTINGS_REQ) {
        handleRoomSettings(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::AVATAR_UPLOAD_REQ) {
        handleAvatarUpload(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::AVATAR_GET_REQ) {
        handleAvatarGet(session, msg["data"].toObject());
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
        // 踢掉旧连接：先发送强制下线通知，再断开
        {
            QMutexLocker locker(&m_mutex);
            if (m_sessions.contains(username)) {
                ClientSession *old = m_sessions[username];
                QJsonObject kickData;
                kickData["reason"] = QStringLiteral("您的账号在其他地方登录，当前连接已被断开");
                old->sendMessage(Protocol::makeMessage(Protocol::MsgType::FORCE_OFFLINE, kickData));
                // 标记旧 session 为已踢出，避免断开时清理房间
                old->setKicked(true);
                old->disconnectFromServer();
                m_sessions.remove(username);
            }
        }
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
        bool alreadyInRoom = m_roomMgr->isUserInRoom(roomId, session->userId());

        m_roomMgr->addUserToRoom(roomId, session->userId(), session->username());
        m_db->joinRoom(roomId, session->userId());

        // 对于系统创建的房间（creator_id <= 1），如果还没有任何管理员
        // 则自动将首个加入的真实用户设为管理员
        if (!alreadyInRoom && !m_db->isRoomAdmin(roomId, session->userId())) {
            if (!m_db->hasAnyAdmin(roomId)) {
                m_db->setRoomAdmin(roomId, session->userId(), true);
                qInfo() << "[Server] 自动将" << session->username()
                         << "设为房间" << roomId << "管理员";
            }
        }

        rspData["success"]  = true;
        rspData["roomId"]   = roomId;
        rspData["roomName"] = m_roomMgr->roomName(roomId);
        rspData["isAdmin"]  = m_db->isRoomAdmin(roomId, session->userId());
        rspData["newJoin"]  = !alreadyInRoom;

        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));

        // 仅在用户首次加入时通知房间其他成员
        if (!alreadyInRoom) {
            QJsonObject notifyData;
            notifyData["roomId"]   = roomId;
            notifyData["username"] = session->username();
            broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_JOINED, notifyData), session);
        }
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

    // 检查房间文件大小限制
    qint64 maxSize = m_db->getRoomMaxFileSize(roomId);
    if (maxSize > 0 && fileSize > maxSize) {
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = QString("文件大小超过房间限制(%1MB)").arg(maxSize / 1024 / 1024);
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }

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

    QString dbFileName = m_db->getFileName(fileId);
    if (!filePath.isEmpty()) {
        QFile file(filePath);
        if (file.open(QIODevice::ReadOnly)) {
            QByteArray content = file.readAll();
            file.close();
            rspData["success"]  = true;
            rspData["fileId"]   = fileId;
            rspData["fileName"] = dbFileName.isEmpty() ? data["fileName"].toString() : dbFileName;
            rspData["fileData"] = QString::fromLatin1(content.toBase64());
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

// ==================== 大文件分块传输 ====================

void ChatServer::handleFileUploadStart(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId       = data["roomId"].toInt();
    QString fileName = data["fileName"].toString();
    qint64 fileSize  = static_cast<qint64>(data["fileSize"].toDouble());

    QJsonObject rspData;

    if (fileSize > Protocol::MAX_LARGE_FILE) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件超过大小限制");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    // 检查房间文件大小限制
    qint64 maxSize = m_db->getRoomMaxFileSize(roomId);
    if (maxSize > 0 && fileSize > maxSize) {
        rspData["success"] = false;
        rspData["error"] = QString("文件大小超过房间限制(%1MB)").arg(maxSize / 1024 / 1024);
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    // 生成上传ID和临时文件路径
    QString uploadId = QUuid::createUuid().toString(QUuid::WithoutBraces);

    QDir dir("server_files");
    if (!dir.exists()) dir.mkpath(".");
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = dir.filePath(safeName);

    auto *file = new QFile(filePath);
    if (!file->open(QIODevice::WriteOnly)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("服务器无法创建文件");
        delete file;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    UploadState state;
    state.roomId   = roomId;
    state.userId   = session->userId();
    state.username = session->username();
    state.fileName = fileName;
    state.filePath = filePath;
    state.fileSize = fileSize;
    state.received = 0;
    state.file     = file;
    m_uploads[uploadId] = state;

    rspData["success"]  = true;
    rspData["uploadId"] = uploadId;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));

    qInfo() << "[Server] 大文件上传开始:" << fileName << fileSize << "bytes, uploadId:" << uploadId;
}

void ChatServer::handleFileUploadChunk(ClientSession *session, const QJsonObject &data) {
    Q_UNUSED(session)
    QString uploadId = data["uploadId"].toString();

    QJsonObject rspData;
    rspData["uploadId"] = uploadId;

    if (!m_uploads.contains(uploadId)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("无效的上传ID");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
        return;
    }

    UploadState &state = m_uploads[uploadId];
    QByteArray chunk = QByteArray::fromBase64(data["chunkData"].toString().toLatin1());

    if (state.file && state.file->isOpen()) {
        state.file->write(chunk);
        state.received += chunk.size();
    }

    rspData["success"]  = true;
    rspData["received"] = static_cast<double>(state.received);
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
}

void ChatServer::handleFileUploadEnd(ClientSession *session, const QJsonObject &data) {
    QString uploadId = data["uploadId"].toString();

    if (!m_uploads.contains(uploadId)) return;

    UploadState state = m_uploads.take(uploadId);
    if (state.file) {
        state.file->close();
        delete state.file;
    }

    // 保存文件信息到数据库
    int fileId = m_db->saveFile(state.roomId, state.userId, state.fileName, state.filePath, state.fileSize);
    int msgId = m_db->saveMessage(state.roomId, state.userId, state.fileName, "file",
                                   state.fileName, state.fileSize, fileId);

    // 通知房间所有成员有新文件
    QJsonObject notifyData;
    notifyData["id"]          = msgId;
    notifyData["roomId"]      = state.roomId;
    notifyData["sender"]      = state.username;
    notifyData["fileName"]    = state.fileName;
    notifyData["fileSize"]    = static_cast<double>(state.fileSize);
    notifyData["fileId"]      = fileId;
    notifyData["contentType"] = QStringLiteral("file");
    notifyData["content"]     = state.fileName;

    broadcastToRoom(state.roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));

    qInfo() << "[Server] 大文件上传完成:" << state.fileName << state.fileSize << "bytes";
    Q_UNUSED(session)
}

void ChatServer::handleFileDownloadChunk(ClientSession *session, const QJsonObject &data) {
    int fileId    = data["fileId"].toInt();
    qint64 offset = static_cast<qint64>(data["offset"].toDouble());
    int chunkSize = data["chunkSize"].toInt();
    if (chunkSize <= 0) chunkSize = Protocol::FILE_CHUNK_SIZE;

    QString filePath = m_db->getFilePath(fileId);
    QJsonObject rspData;
    rspData["fileId"] = fileId;

    if (filePath.isEmpty()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件记录不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_RSP, rspData));
        return;
    }

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_RSP, rspData));
        return;
    }

    file.seek(offset);
    QByteArray chunk = file.read(chunkSize);
    file.close();

    rspData["success"]   = true;
    rspData["offset"]    = static_cast<double>(offset);
    rspData["chunkData"] = QString::fromLatin1(chunk.toBase64());
    rspData["chunkSize"] = chunk.size();
    rspData["fileSize"]  = static_cast<double>(file.size());

    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_RSP, rspData));
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

        // 管理员撤回不再发额外系统消息，撤回通知已足够
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("无法撤回（超时或非本人消息）");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RECALL_RSP, rspData));
    }
}

// ==================== 广播/发送 ====================

// ==================== 管理员功能 ====================

void ChatServer::handleSetAdmin(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString targetUser = data["username"].toString();
    bool setAdmin = data["isAdmin"].toBool(true);

    QJsonObject rspData;
    rspData["roomId"] = roomId;
    rspData["username"] = targetUser;

    // 管理员可以设置其他管理员（不再仅限创建者）
    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只有管理员可以设置其他管理员");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
        return;
    }

    // 查找目标用户 ID
    int targetUserId = 0;
    {
        QMutexLocker locker(&m_mutex);
        if (m_sessions.contains(targetUser)) {
            targetUserId = m_sessions[targetUser]->userId();
        }
    }
    if (targetUserId <= 0) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("用户不在线");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
        return;
    }

    m_db->setRoomAdmin(roomId, targetUserId, setAdmin);

    rspData["success"] = true;
    rspData["isAdmin"] = setAdmin;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));

    // 通知目标用户其管理员状态变更
    QJsonObject notifyData;
    notifyData["roomId"] = roomId;
    notifyData["isAdmin"] = setAdmin;
    sendToUser(targetUser, Protocol::makeMessage(Protocol::MsgType::ADMIN_STATUS, notifyData));

    // 广播系统消息通知全体
    QString sysContent = setAdmin
        ? QString("管理员 %1 已将 %2 设为管理员").arg(session->username(), targetUser)
        : QString("管理员 %1 已取消 %2 的管理员权限").arg(session->username(), targetUser);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));
}

void ChatServer::handleDeleteMessages(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString mode = data["mode"].toString(); // "selected", "all", "before", "after"

    QJsonObject rspData;
    rspData["roomId"] = roomId;

    // 验证管理员权限
    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("您没有管理员权限");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_RSP, rspData));
        return;
    }

    int deletedCount = 0;

    if (mode == "selected") {
        // 删除选定的消息
        QList<int> ids;
        for (const QJsonValue &v : data["messageIds"].toArray())
            ids.append(v.toInt());
        if (m_db->deleteMessages(roomId, ids))
            deletedCount = ids.size();
    } else if (mode == "all") {
        // 清空所有消息
        deletedCount = m_db->deleteAllMessages(roomId);
    } else if (mode == "before") {
        // 删除某时间之前的消息
        qint64 ts = static_cast<qint64>(data["timestamp"].toDouble());
        QDateTime dt = QDateTime::fromMSecsSinceEpoch(ts);
        deletedCount = m_db->deleteMessagesBefore(roomId, dt);
    } else if (mode == "after") {
        // 删除某时间之后的消息
        qint64 ts = static_cast<qint64>(data["timestamp"].toDouble());
        QDateTime dt = QDateTime::fromMSecsSinceEpoch(ts);
        deletedCount = m_db->deleteMessagesAfter(roomId, dt);
    }

    rspData["success"] = true;
    rspData["deletedCount"] = deletedCount;
    rspData["mode"] = mode;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_RSP, rspData));

    // 通知房间所有成员刷新消息
    QJsonObject notifyData;
    notifyData["roomId"] = roomId;
    notifyData["mode"] = mode;
    notifyData["deletedCount"] = deletedCount;
    notifyData["operator"] = session->username();
    if (mode == "selected") {
        notifyData["messageIds"] = data["messageIds"].toArray();
    }
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_NOTIFY, notifyData), session);

    // 广播系统消息通知全体
    QString sysContent;
    if (mode == "all")
        sysContent = QString("管理员 %1 清空了所有聊天记录").arg(session->username());
    else if (mode == "selected")
        sysContent = QString("管理员 %1 删除了 %2 条消息").arg(session->username()).arg(deletedCount);
    else if (mode == "before")
        sysContent = QString("管理员 %1 删除了 %2 条旧消息").arg(session->username()).arg(deletedCount);
    else
        sysContent = QString("管理员 %1 删除了 %2 条近期消息").arg(session->username()).arg(deletedCount);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));
}

// ==================== 房间设置 ====================

void ChatServer::handleRoomSettings(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QJsonObject rspData;
    rspData["roomId"] = roomId;

    // 设置操作需要管理员权限
    if (data.contains("maxFileSize")) {
        if (!m_db->isRoomAdmin(roomId, session->userId())) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("您没有管理员权限");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }

        qint64 maxFileSize = static_cast<qint64>(data["maxFileSize"].toDouble());
        m_db->setRoomMaxFileSize(roomId, maxFileSize);

        rspData["success"] = true;
        rspData["maxFileSize"] = static_cast<double>(maxFileSize);
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));

        // 通知房间所有人
        QJsonObject notifyData;
        notifyData["roomId"] = roomId;
        notifyData["maxFileSize"] = static_cast<double>(maxFileSize);
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_NOTIFY, notifyData));

        // 系统消息
        QString sizeStr = maxFileSize > 0
            ? QString("%1MB").arg(maxFileSize / 1024 / 1024)
            : QStringLiteral("无限制");
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
            {{"roomId", roomId}, {"content", QString("管理员 %1 设置了文件大小上限: %2")
                .arg(session->username(), sizeStr)}}));
    } else {
        // 查询设置
        rspData["success"] = true;
        rspData["maxFileSize"] = static_cast<double>(m_db->getRoomMaxFileSize(roomId));
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
    }
}

// ==================== 头像功能 ====================

void ChatServer::handleAvatarUpload(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString avatarBase64 = data["avatarData"].toString();
    QByteArray avatarData = QByteArray::fromBase64(avatarBase64.toLatin1());

    QJsonObject rspData;

    if (avatarData.size() > 256 * 1024) { // 限制 256KB
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("头像数据过大，请选择较小的图片");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::AVATAR_UPLOAD_RSP, rspData));
        return;
    }

    if (m_db->setUserAvatar(session->userId(), avatarData)) {
        rspData["success"] = true;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::AVATAR_UPLOAD_RSP, rspData));

        // 通知所有人头像已更新（通过所有房间广播）
        QJsonObject notifyData;
        notifyData["username"] = session->username();
        notifyData["avatarData"] = avatarBase64;

        // 向所有在线用户广播头像更新
        QMutexLocker locker(&m_mutex);
        for (auto it = m_sessions.begin(); it != m_sessions.end(); ++it) {
            if (it.key() != session->username()) {
                QMetaObject::invokeMethod(it.value(), "sendMessage", Qt::QueuedConnection,
                    Q_ARG(QJsonObject, Protocol::makeMessage(Protocol::MsgType::AVATAR_UPDATE_NOTIFY, notifyData)));
            }
        }
    } else {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("保存头像失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::AVATAR_UPLOAD_RSP, rspData));
    }
}

void ChatServer::handleAvatarGet(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString username = data["username"].toString();
    QByteArray avatarData = m_db->getUserAvatarByName(username);

    QJsonObject rspData;
    rspData["username"] = username;
    if (!avatarData.isEmpty()) {
        rspData["success"] = true;
        rspData["avatarData"] = QString::fromLatin1(avatarData.toBase64());
    } else {
        rspData["success"] = false;
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::AVATAR_GET_RSP, rspData));
}

// ==================== 广播/实现 ====================

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
