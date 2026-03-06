#include "ChatServer.h"
#include "ClientSession.h"
#include "DatabaseManager.h"
#include "RoomManager.h"
#include "Protocol.h"
#include "Message.h"

#include <QThread>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QRandomGenerator>
#include <QDebug>
#include <QFile>
#include <QDir>
#include <QDate>
#include <QCoreApplication>
#include <QRegularExpression>
#include <QWebSocketServer>
#include <QWebSocket>
#include <QImage>
#include <QBuffer>

ChatServer::ChatServer(QObject *parent)
    : QTcpServer(parent)
{
    m_db      = new DatabaseManager(this);
    m_roomMgr = new RoomManager(this);
}

ChatServer::~ChatServer() {
    stopServer();
}

bool ChatServer::startServer(quint16 port, quint16 wsPort) {
    // 初始化数据库
    if (!m_db->initialize()) {
        qCritical() << "[Server] 数据库初始化失败";
        return false;
    }
    // 初始化房间管理器（从数据库加载房间列表）
    m_roomMgr->loadRooms(m_db);

    if (!listen(QHostAddress::Any, port)) {
        qCritical() << "[Server] TCP 监听端口失败:" << port << errorString();
        return false;
    }
    qInfo() << "[Server] TCP 服务器已启动，监听端口:" << port;

    // 启动 WebSocket 服务器（默认 TCP 端口 + 1）
    if (wsPort == 0) wsPort = port + 1;
    m_wsServer = new QWebSocketServer(
        QStringLiteral("ChatServer-WS"), QWebSocketServer::NonSecureMode, this);
    if (!m_wsServer->listen(QHostAddress::Any, wsPort)) {
        qCritical() << "[Server] WebSocket 监听端口失败:" << wsPort << m_wsServer->errorString();
        return false;
    }
    connect(m_wsServer, &QWebSocketServer::newConnection,
            this, &ChatServer::onNewWebSocketConnection);
    qInfo() << "[Server] WebSocket 服务器已启动，监听端口:" << wsPort;

    return true;
}

void ChatServer::stopServer() {
    close();
    if (m_wsServer) {
        m_wsServer->close();
    }
    QMutexLocker locker(&m_mutex);
    for (auto *s : std::as_const(m_sessions))
        s->disconnectFromServer();
    m_sessions.clear();
}

// ==================== 新连接 ====================

void ChatServer::incomingConnection(qintptr socketDescriptor) {
    qInfo() << "[Server] 新 TCP 连接:" << socketDescriptor;

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

void ChatServer::onNewWebSocketConnection() {
    while (m_wsServer->hasPendingConnections()) {
        QWebSocket *ws = m_wsServer->nextPendingConnection();
        qInfo() << "[Server] 新 WebSocket 连接:" << ws->peerAddress().toString();

        // QWebSocket 已拥有活跃的 socket notifier，不能 moveToThread
        // 直接在主线程处理 WebSocket 会话
        ClientSession *session = new ClientSession(ws, this);
        session->init();

        connect(session, &ClientSession::authenticated,  this, &ChatServer::onClientAuthenticated);
        connect(session, &ClientSession::disconnected,   this, &ChatServer::onClientDisconnected);
        connect(session, &ClientSession::messageReceived,this, &ChatServer::onClientMessage);
    }
}

// ==================== 会话事件 ====================

void ChatServer::onClientAuthenticated(ClientSession *session) {
    {
        QMutexLocker locker(&m_mutex);
        m_sessions[session->username()] = session;
    }
    qInfo() << "[Server] 用户认证成功:" << session->username();

    // 将用户加入其所有房间的内存缓存，并广播 USER_ONLINE
    QJsonArray rooms = m_db->getUserJoinedRooms(session->userId());
    for (const QJsonValue &v : rooms) {
        int roomId = v.toObject()["roomId"].toInt();
        m_roomMgr->addUserToRoom(roomId, session->userId(), session->username());
        QJsonObject data;
        data["roomId"]       = roomId;
        data["username"]     = session->username();
        data["displayName"]  = session->displayName();
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_ONLINE, data), session);
    }

    // 通知好友上线
    QJsonArray friends = m_db->getFriendList(session->userId());
    for (const QJsonValue &v : friends) {
        QJsonObject fr = v.toObject();
        QJsonObject notifyData;
        notifyData["username"] = session->username();
        notifyData["displayName"] = session->displayName();
        sendToUser(fr["username"].toString(),
                   Protocol::makeMessage(Protocol::MsgType::FRIEND_ONLINE_NOTIFY, notifyData));
    }
}

void ChatServer::onClientDisconnected(ClientSession *session) {
    QString username = session->username();
    int userId = session->userId();
    {
        QMutexLocker locker(&m_mutex);
        if (m_sessions.value(username) == session)
            m_sessions.remove(username);
    }

    // 清理该用户进行中的上传状态
    QList<QString> staleUploads;
    for (auto it = m_uploads.begin(); it != m_uploads.end(); ++it) {
        if (it.value().userId == userId)
            staleUploads.append(it.key());
    }
    for (const QString &uploadId : staleUploads) {
        UploadState state = m_uploads.take(uploadId);
        if (state.file) {
            state.file->close();
            delete state.file;
        }
        // 删除不完整的文件
        if (!state.filePath.isEmpty())
            QFile::remove(state.filePath);
        qInfo() << "[Server] 清理断连用户上传:" << state.fileName;
    }

    // 被踢出的 session 不广播（新的 session 会继承房间状态）
    if (!username.isEmpty() && !session->isKicked()) {
        // 通知好友下线
        QJsonArray friends = m_db->getFriendList(userId);
        for (const QJsonValue &v : friends) {
            QJsonObject fr = v.toObject();
            QJsonObject notifyData;
            notifyData["username"] = username;
            sendToUser(fr["username"].toString(),
                       Protocol::makeMessage(Protocol::MsgType::FRIEND_OFFLINE_NOTIFY, notifyData));
        }

        // 从 DB 获取用户所有房间，广播 USER_OFFLINE（不是 USER_LEFT）
        QJsonArray rooms = m_db->getUserJoinedRooms(userId);
        for (const QJsonValue &v : rooms) {
            int roomId = v.toObject()["roomId"].toInt();
            m_roomMgr->removeUserFromRoom(roomId, userId);
            QJsonObject data;
            data["roomId"]   = roomId;
            data["username"] = username;
            broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_OFFLINE, data));
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
    } else if (type == Protocol::MsgType::FILE_UPLOAD_CANCEL) {
        handleFileUploadCancel(session, msg["data"].toObject());
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
    } else if (type == Protocol::MsgType::DELETE_ROOM_REQ) {
        handleDeleteRoom(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::RENAME_ROOM_REQ) {
        handleRenameRoom(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::SET_ROOM_PASSWORD_REQ) {
        handleSetRoomPassword(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::GET_ROOM_PASSWORD_REQ) {
        handleGetRoomPassword(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::KICK_USER_REQ) {
        handleKickUser(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::AVATAR_UPLOAD_REQ) {
        handleAvatarUpload(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::AVATAR_GET_REQ) {
        handleAvatarGet(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::CHANGE_NICKNAME_REQ) {
        handleChangeNickname(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::CHANGE_UID_REQ) {
        handleChangeUid(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::CHANGE_PASSWORD_REQ) {
        handleChangePassword(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::USER_SEARCH_REQ) {
        handleUserSearch(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_SEARCH_REQ) {
        handleRoomSearch(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_AVATAR_UPLOAD_REQ) {
        handleRoomAvatarUpload(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_AVATAR_GET_REQ) {
        handleRoomAvatarGet(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_REQUEST_REQ) {
        handleFriendRequest(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_ACCEPT_REQ) {
        handleFriendAccept(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_REJECT_REQ) {
        handleFriendReject(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_REMOVE_REQ) {
        handleFriendRemove(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_LIST_REQ) {
        handleFriendList(session);
    } else if (type == Protocol::MsgType::FRIEND_PENDING_REQ) {
        handleFriendPending(session);
    } else if (type == Protocol::MsgType::FRIEND_CHAT_MSG) {
        handleFriendChatMessage(session, msg);
    } else if (type == Protocol::MsgType::FRIEND_HISTORY_REQ) {
        handleFriendHistory(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_FILE_SEND) {
        handleFriendFileSend(session, msg);
    } else if (type == Protocol::MsgType::FRIEND_FILE_UPLOAD_START) {
        handleFriendFileUploadStart(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::FRIEND_RECALL_REQ) {
        handleFriendRecall(session, msg["data"].toObject());
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
        QString displayName = m_db->getDisplayName(userId);
        // 踢掉旧连接：先发送强制下线通知，再断开
        // 注意：必须先释放 mutex 再断开，因为 WebSocket session 在主线程
        // disconnectFromServer() 会同步触发 onClientDisconnected()，后者也要加锁
        ClientSession *oldSession = nullptr;
        {
            QMutexLocker locker(&m_mutex);
            if (m_sessions.contains(username)) {
                oldSession = m_sessions.take(username);
                oldSession->setKicked(true);
            }
        }
        if (oldSession) {
            QJsonObject kickData;
            kickData["reason"] = QStringLiteral("您的账号在其他地方登录，当前连接已被断开");
            oldSession->sendMessage(Protocol::makeMessage(Protocol::MsgType::FORCE_OFFLINE, kickData));
            oldSession->disconnectFromServer();
        }
        session->setAuthenticated(userId, username, displayName);
        rspData["success"]     = true;
        rspData["userId"]      = userId;
        rspData["username"]    = username;
        rspData["displayName"] = displayName;
        emit session->authenticated(session);
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户ID或密码错误");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LOGIN_RSP, rspData));
}

void ChatServer::handleRegister(ClientSession *session, const QJsonObject &data) {
    QString username = data["username"].toString();       // uniqueId
    QString displayName = data["displayName"].toString(); // 昵称
    QString password = data["password"].toString();

    QJsonObject rspData;

    // 验证唯一ID格式：6-12位，仅允许字母、数字、下划线
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(username).hasMatch()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户ID必须为6-20位，只能包含字母、数字和下划线");
    } else if (displayName.trimmed().isEmpty() || displayName.trimmed().length() < 1) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("请输入昵称");
    } else if (password.length() < 4) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("密码至少4个字符");
    } else {
        int userId = m_db->registerUser(username, displayName.trimmed(), password);
        if (userId > 0) {
            rspData["success"]  = true;
            rspData["userId"]   = userId;
            rspData["username"] = username;

            // 注册成功后自动创建个人聊天室
            QString roomName = QString("%1的聊天室").arg(displayName.trimmed());
            int roomId = m_db->createRoom(roomName, userId);
            if (roomId > 0) {
                m_roomMgr->addRoom(roomId, roomName, userId);
                m_db->joinRoom(roomId, userId);
                m_db->setRoomAdmin(roomId, userId, true);
                qInfo() << "[Server] 为用户" << username << "创建了个人聊天室 ID:" << roomId;
            }
        } else {
            rspData["success"] = false;
            rspData["error"]   = QStringLiteral("用户ID已存在");
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
    fullData["id"]         = msgId;
    fullData["sender"]     = session->username();      // uniqueId
    fullData["senderName"] = session->displayName();   // 昵称
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
        m_db->setRoomAdmin(roomId, session->userId(), true); // 创建者写入管理员表
        rspData["success"]  = true;
        rspData["roomId"]   = roomId;
        rspData["roomName"] = roomName;
        rspData["isAdmin"]  = true; // 创建者自动为管理员
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
        // 检查 DB 持久化成员资格
        bool alreadyMember = m_db->isUserInRoom(roomId, session->userId());

        // 首次加入时检查密码
        if (!alreadyMember && m_db->roomHasPassword(roomId)) {
            QString providedPwd = data["password"].toString();
            QString roomPwd = m_db->getRoomPassword(roomId);
            if (providedPwd.isEmpty()) {
                rspData["success"] = false;
                rspData["roomId"]  = roomId;
                rspData["needPassword"] = true;
                rspData["error"]   = QStringLiteral("该聊天室需要密码才能加入");
                session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));
                return;
            }
            if (providedPwd != roomPwd) {
                rspData["success"] = false;
                rspData["roomId"]  = roomId;
                rspData["needPassword"] = true;
                rspData["error"]   = QStringLiteral("密码错误");
                session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));
                return;
            }
        }

        m_roomMgr->addUserToRoom(roomId, session->userId(), session->username());
        m_db->joinRoom(roomId, session->userId());

        rspData["success"]  = true;
        rspData["roomId"]   = roomId;
        rspData["roomName"] = m_roomMgr->roomName(roomId);
        rspData["isAdmin"]  = m_db->isRoomAdmin(roomId, session->userId());
        rspData["newJoin"]  = !alreadyMember;

        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));

        // 仅在用户首次加入时通知房间其他成员
        if (!alreadyMember) {
            QJsonObject notifyData;
            notifyData["roomId"]      = roomId;
            notifyData["username"]    = session->username();
            notifyData["displayName"] = session->displayName();
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
    int userId = session->userId();

    // 从内存中移除（在线跟踪）
    m_roomMgr->removeUserFromRoom(roomId, userId);

    // 检查该用户是否是管理员
    bool wasAdmin = m_db->isRoomAdmin(roomId, userId);

    // 移除管理员状态（issue 1: 离开即解除管理员）
    m_db->setRoomAdmin(roomId, userId, false);

    // 从 DB 中移除成员关系
    m_db->leaveRoom(roomId, userId);

    // 通知房间剩余成员该用户离开
    QJsonObject notifyData;
    notifyData["roomId"]      = roomId;
    notifyData["username"]    = session->username();
    notifyData["displayName"] = session->displayName();
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LEFT, notifyData));

    // 检查房间是否还有成员（issue 2: 最后一人离开自动解散）
    int memberCount = m_db->getRoomMemberCount(roomId);
    if (memberCount == 0) {
        // 没有成员了，自动删除房间
        m_db->deleteRoom(roomId);
        m_roomMgr->removeRoom(roomId);
        qInfo() << "[Server] 聊天室" << roomId << "因无成员自动解散";
    } else if (wasAdmin) {
        // issue 4: 如果离开的是管理员，检查房间是否还有管理员
        QList<int> admins = m_db->getRoomAdmins(roomId);
        if (admins.isEmpty()) {
            // 没有管理员了，随机指派一个成员为管理员
            QJsonArray members = m_db->getRoomMembers(roomId);
            if (!members.isEmpty()) {
                int randomIdx = QRandomGenerator::global()->bounded(members.size());
                QJsonObject randomMember = members[randomIdx].toObject();
                int newAdminId = randomMember["userId"].toInt();
                QString newAdminName = randomMember["username"].toString();
                QString newAdminDisplayName = m_db->getDisplayName(newAdminId);

                m_db->setRoomAdmin(roomId, newAdminId, true);

                // 通知新管理员
                QJsonObject adminNotify;
                adminNotify["roomId"] = roomId;
                adminNotify["isAdmin"] = true;
                sendToUser(newAdminName, Protocol::makeMessage(Protocol::MsgType::ADMIN_STATUS, adminNotify));

                // 广播系统消息
                broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
                    {{"roomId", roomId}, {"content", QString("%1 已被自动指定为管理员").arg(newAdminDisplayName)}}));
            }
        }
    }

    // 发送响应给离开的用户
    QJsonObject rspData;
    rspData["roomId"]  = roomId;
    rspData["success"] = true;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LEAVE_ROOM_RSP, rspData));
}

void ChatServer::handleRoomList(ClientSession *session) {
    // 只返回用户已加入的房间
    QJsonArray roomArr = m_db->getUserJoinedRooms(session->userId());
    QJsonObject rspData;
    rspData["rooms"] = roomArr;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_LIST_RSP, rspData));
}

void ChatServer::handleUserList(ClientSession *session, const QJsonObject &data) {
    int roomId = data["roomId"].toInt();

    // 从 DB 获取所有房间成员
    QJsonArray members = m_db->getRoomMembers(roomId);
    QList<int> admins = m_db->getRoomAdmins(roomId);

    QJsonArray userArr;
    {
        QMutexLocker locker(&m_mutex);
        for (const QJsonValue &v : members) {
            QJsonObject member = v.toObject();
            QString username = member["username"].toString();
            int userId = member["userId"].toInt();

            QJsonObject userObj;
            userObj["username"]    = username;
            userObj["displayName"] = member["displayName"].toString();
            userObj["isAdmin"]     = admins.contains(userId);
            userObj["isOnline"]    = m_sessions.contains(username);
            userArr.append(userObj);
        }
    }

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

QString ChatServer::fileTypeSubDir(const QString &fileName) {
    static const QStringList imgExts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    QString suffix = QFileInfo(fileName).suffix().toLower();
    if (imgExts.contains(suffix)) return QStringLiteral("Image");
    if (vidExts.contains(suffix)) return QStringLiteral("Video");
    return QStringLiteral("File");
}

QString ChatServer::serverFileDir(int roomId, const QString &fileName) const {
    // server_files/{roomId}/Image|Video|File/{yyyy-MM}/
    QString typeDir = fileTypeSubDir(fileName);
    QString yearMonth = QDate::currentDate().toString("yyyy-MM");
    QString dir = QCoreApplication::applicationDirPath()
                  + "/server_files/"
                  + QString::number(roomId) + "/"
                  + typeDir + "/"
                  + yearMonth;
    QDir d(dir);
    if (!d.exists()) d.mkpath(".");
    return dir;
}

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

    // 保存文件到服务器磁盘（多级目录：{roomId}/类型/年月/）
    QString targetDir = serverFileDir(roomId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    QFile file(filePath);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QByteArray::fromBase64(fileData.toUtf8()));
        file.close();
    }

    // 保存文件信息到数据库
    int fileId = m_db->saveFile(roomId, session->userId(), fileName, filePath, fileSize);

    // 根据文件后缀确定 contentType
    QString contentType = QStringLiteral("file");
    QString typeDir = fileTypeSubDir(fileName);
    if (typeDir == QLatin1String("Image"))
        contentType = QStringLiteral("image");
    else if (typeDir == QLatin1String("Video"))
        contentType = QStringLiteral("video");

    // 自动生成缩略图
    QString thumbnail;
    if (contentType == QLatin1String("image") && fileSize < 20 * 1024 * 1024) {
        // 从已保存的磁盘文件读取，避免二次 base64 解码
        QImage img(filePath);
        if (!img.isNull()) {
            QImage thumb = img.scaled(200, 200, Qt::KeepAspectRatio, Qt::FastTransformation);
            QByteArray thumbData;
            QBuffer buf(&thumbData);
            buf.open(QIODevice::WriteOnly);
            thumb.save(&buf, "JPEG", 60);
            thumbnail = QString::fromLatin1(thumbData.toBase64());
        }
    }
    // QImage 失败或非图片类型时，使用客户端提供的缩略图
    if (thumbnail.isEmpty() && data.contains("thumbnail")) {
        thumbnail = data["thumbnail"].toString();
    }

    // 保存消息记录（含缩略图）
    int msgId = m_db->saveMessage(roomId, session->userId(), fileName, contentType, fileName, fileSize, fileId, thumbnail);

    // 通知房间所有成员有新文件
    QJsonObject notifyData;
    notifyData["id"]          = msgId;
    notifyData["roomId"]      = roomId;
    notifyData["sender"]      = session->username();
    notifyData["senderName"]  = session->displayName();
    notifyData["fileName"]    = fileName;
    notifyData["fileSize"]    = static_cast<double>(fileSize);
    notifyData["fileId"]      = fileId;
    notifyData["contentType"] = contentType;
    notifyData["content"]     = fileName;

    if (!thumbnail.isEmpty())
        notifyData["thumbnail"] = thumbnail;

    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));
}

void ChatServer::handleFileDownload(ClientSession *session, const QJsonObject &data) {
    int fileId = data["fileId"].toInt();

    // 负数 fileId 表示好友文件
    bool isFriendFile = (fileId < 0);
    int dbFileId = isFriendFile ? -fileId : fileId;

    QString filePath = m_db->getFilePath(dbFileId, isFriendFile);
    QJsonObject rspData;

    QString dbFileName = m_db->getFileName(dbFileId, isFriendFile);
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
            rspData["fileId"]  = fileId;
            rspData["error"]   = QStringLiteral("文件不存在");
        }
    } else {
        rspData["success"] = false;
        rspData["fileId"]  = fileId;
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

    // 生成上传ID和临时文件路径（多级目录：{roomId}/类型/年月/）
    QString uploadId = QUuid::createUuid().toString(QUuid::WithoutBraces);

    QString targetDir = serverFileDir(roomId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    auto *file = new QFile(filePath);
    if (!file->open(QIODevice::WriteOnly)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("服务器无法创建文件");
        delete file;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    UploadState state;
    state.roomId      = roomId;
    state.userId      = session->userId();
    state.username    = session->username();
    state.displayName = session->displayName();
    state.fileName    = fileName;
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

    // 根据文件后缀确定 contentType
    QString contentType = QStringLiteral("file");
    QString typeDir = fileTypeSubDir(state.fileName);
    if (typeDir == QLatin1String("Image"))
        contentType = QStringLiteral("image");
    else if (typeDir == QLatin1String("Video"))
        contentType = QStringLiteral("video");

    // 缩略图：图片自动生成（限制大小），视频由客户端提供
    QString thumbnail;
    if (contentType == QLatin1String("image") && state.fileSize < 20 * 1024 * 1024) {
        QImage img(state.filePath);
        if (!img.isNull()) {
            QImage thumb = img.scaled(200, 200, Qt::KeepAspectRatio, Qt::FastTransformation);
            QByteArray thumbData;
            QBuffer buf(&thumbData);
            buf.open(QIODevice::WriteOnly);
            thumb.save(&buf, "JPEG", 60);
            thumbnail = QString::fromLatin1(thumbData.toBase64());
        }
    }
    // QImage 失败或非图片类型时，使用客户端提供的缩略图
    if (thumbnail.isEmpty() && data.contains("thumbnail")) {
        thumbnail = data["thumbnail"].toString();
    }

    // 保存文件信息到数据库
    if (state.roomId < 0) {
        // 好友文件上传 (roomId = -friendshipId)
        int friendshipId = -state.roomId;
        int fileId = m_db->saveFriendFile(friendshipId, state.userId, state.fileName, state.filePath, state.fileSize);
        int msgId = m_db->saveFriendMessage(friendshipId, state.userId, state.fileName, contentType,
                                             state.fileName, state.fileSize, fileId, thumbnail);

        // 找到好友用户名
        QJsonArray friends = m_db->getFriendList(state.userId);
        QString friendUsername;
        for (const QJsonValue &v : friends) {
            if (v.toObject()["friendshipId"].toInt() == friendshipId) {
                friendUsername = v.toObject()["username"].toString();
                break;
            }
        }

        QJsonObject notifyData;
        notifyData["id"]           = msgId;
        notifyData["friendshipId"] = friendshipId;
        notifyData["sender"]       = state.username;
        notifyData["senderName"]   = state.displayName;
        notifyData["friendUsername"] = friendUsername;
        notifyData["fileName"]     = state.fileName;
        notifyData["fileSize"]     = static_cast<double>(state.fileSize);
        notifyData["fileId"]       = -fileId;  // 负数标识好友文件
        notifyData["contentType"]  = contentType;
        notifyData["content"]      = state.fileName;
        if (!thumbnail.isEmpty())
            notifyData["thumbnail"] = thumbnail;

        QJsonObject notifyMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, notifyData);
        sendToUser(state.username, notifyMsg);
        if (!friendUsername.isEmpty())
            sendToUser(friendUsername, notifyMsg);

        qInfo() << "[Server] 好友大文件上传完成:" << state.fileName << state.fileSize << "bytes";
    } else {
        // 房间文件上传
        int fileId = m_db->saveFile(state.roomId, state.userId, state.fileName, state.filePath, state.fileSize);
        int msgId = m_db->saveMessage(state.roomId, state.userId, state.fileName, contentType,
                                       state.fileName, state.fileSize, fileId, thumbnail);

        // 通知房间所有成员有新文件
        QJsonObject notifyData;
        notifyData["id"]          = msgId;
        notifyData["roomId"]      = state.roomId;
        notifyData["sender"]      = state.username;
        notifyData["senderName"]  = state.displayName;
        notifyData["fileName"]    = state.fileName;
        notifyData["fileSize"]    = static_cast<double>(state.fileSize);
        notifyData["fileId"]      = fileId;
        notifyData["contentType"] = contentType;
        notifyData["content"]     = state.fileName;

        if (!thumbnail.isEmpty())
            notifyData["thumbnail"] = thumbnail;

        broadcastToRoom(state.roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));

        qInfo() << "[Server] 大文件上传完成:" << state.fileName << state.fileSize << "bytes";
    }
    Q_UNUSED(session)
}

void ChatServer::handleFileUploadCancel(ClientSession *session, const QJsonObject &data) {
    Q_UNUSED(session)
    QString uploadId = data["uploadId"].toString();
    if (!m_uploads.contains(uploadId)) return;

    UploadState state = m_uploads.take(uploadId);
    if (state.file) {
        state.file->close();
        delete state.file;
    }
    // 删除不完整的文件
    if (!state.filePath.isEmpty())
        QFile::remove(state.filePath);
    qInfo() << "[Server] 上传已取消:" << state.fileName;
}

void ChatServer::handleFileDownloadChunk(ClientSession *session, const QJsonObject &data) {
    int fileId    = data["fileId"].toInt();
    qint64 offset = static_cast<qint64>(data["offset"].toDouble());
    int chunkSize = data["chunkSize"].toInt();
    if (chunkSize <= 0) chunkSize = Protocol::FILE_CHUNK_SIZE;

    // 负数 fileId 表示好友文件
    bool isFriendFile = (fileId < 0);
    int dbFileId = isFriendFile ? -fileId : fileId;

    QString filePath = m_db->getFilePath(dbFileId, isFriendFile);
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
        // 如果是文件消息，清理服务器文件
        auto fileInfo = m_db->getFileInfoForMessage(messageId);
        if (fileInfo.first > 0) {
            if (!fileInfo.second.isEmpty()) {
                QFile::remove(fileInfo.second);
                qInfo() << "[Server] 撤回消息，已删除文件:" << fileInfo.second;
            }
            m_db->deleteFileRecords({fileInfo.first});
        }

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

    if (setAdmin) {
        // 授权管理员：需要自己是管理员
        if (!m_db->isRoomAdmin(roomId, session->userId())) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("只有管理员可以授权其他管理员");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
            return;
        }
    } else {
        // 解除管理员：只能解除自己的
        if (targetUser != session->username()) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("不能解除其他管理员的权限，只能解除自己的");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
            return;
        }
    }

    // 查找目标用户 ID（支持离线用户）
    int targetUserId = m_db->getUserIdByName(targetUser);
    if (targetUserId <= 0) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("用户不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
        return;
    }

    m_db->setRoomAdmin(roomId, targetUserId, setAdmin);

    rspData["success"] = true;
    rspData["isAdmin"] = setAdmin;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));

    // 通知目标用户其管理员状态变更（如果在线）
    QJsonObject notifyData;
    notifyData["roomId"] = roomId;
    notifyData["isAdmin"] = setAdmin;
    sendToUser(targetUser, Protocol::makeMessage(Protocol::MsgType::ADMIN_STATUS, notifyData));

    // 广播系统消息通知全体
    QString targetDisplayName = m_db->getDisplayNameByUid(targetUser);
    QString sysContent = setAdmin
        ? QString("管理员 %1 已将 %2 设为管理员").arg(session->displayName(), targetDisplayName)
        : QString("%1 已主动放弃管理员权限").arg(targetDisplayName);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));

    // 广播更新后的用户列表给所有房间成员，确保管理员颜色实时刷新
    {
        QJsonArray members = m_db->getRoomMembers(roomId);
        QList<int> adminIds = m_db->getRoomAdmins(roomId);
        QJsonArray userArr;
        {
            QMutexLocker locker(&m_mutex);
            for (const QJsonValue &v : members) {
                QJsonObject member = v.toObject();
                QJsonObject userObj;
                userObj["username"] = member["username"].toString();
                userObj["isAdmin"]  = adminIds.contains(member["userId"].toInt());
                userObj["isOnline"] = m_sessions.contains(member["username"].toString());
                userArr.append(userObj);
            }
        }
        QJsonObject listData;
        listData["roomId"] = roomId;
        listData["users"]  = userArr;
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LIST_RSP, listData));
    }

    // 如果解除自己管理员后房间没有管理员了，随机指派一个
    if (!setAdmin) {
        QList<int> admins = m_db->getRoomAdmins(roomId);
        if (admins.isEmpty()) {
            QJsonArray members = m_db->getRoomMembers(roomId);
            if (!members.isEmpty()) {
                // 排除自己（如果自己还在房间）
                QJsonArray candidates;
                for (const QJsonValue &v : members) {
                    if (v.toObject()["userId"].toInt() != session->userId())
                        candidates.append(v);
                }
                if (candidates.isEmpty()) candidates = members;

                int randomIdx = QRandomGenerator::global()->bounded(candidates.size());
                QJsonObject randomMember = candidates[randomIdx].toObject();
                int newAdminId = randomMember["userId"].toInt();
                QString newAdminName = randomMember["username"].toString();
                QString newAdminDisplayName = m_db->getDisplayName(newAdminId);

                m_db->setRoomAdmin(roomId, newAdminId, true);

                QJsonObject adminNotify;
                adminNotify["roomId"] = roomId;
                adminNotify["isAdmin"] = true;
                sendToUser(newAdminName, Protocol::makeMessage(Protocol::MsgType::ADMIN_STATUS, adminNotify));

                broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
                    {{"roomId", roomId}, {"content", QString("%1 已被自动指定为管理员").arg(newAdminDisplayName)}}));

                // 广播更新后的用户列表
                QList<int> newAdminIds = m_db->getRoomAdmins(roomId);
                QJsonArray userArr2;
                {
                    QMutexLocker locker(&m_mutex);
                    for (const QJsonValue &v2 : members) {
                        QJsonObject m2 = v2.toObject();
                        QJsonObject u2;
                        u2["username"] = m2["username"].toString();
                        u2["isAdmin"]  = newAdminIds.contains(m2["userId"].toInt());
                        u2["isOnline"] = m_sessions.contains(m2["username"].toString());
                        userArr2.append(u2);
                    }
                }
                QJsonObject listData2;
                listData2["roomId"] = roomId;
                listData2["users"]  = userArr2;
                broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LIST_RSP, listData2));
            }
        }
    }
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

    // 先查询待删除消息关联的文件，以便清理
    QList<QPair<int, QString>> fileInfos;
    int deletedCount = 0;

    if (mode == "selected") {
        QList<int> ids;
        for (const QJsonValue &v : data["messageIds"].toArray())
            ids.append(v.toInt());
        fileInfos = m_db->getFileInfoForMessages(roomId, ids);
        if (m_db->deleteMessages(roomId, ids))
            deletedCount = ids.size();
    } else if (mode == "all") {
        fileInfos = m_db->getAllFileInfoForRoom(roomId);
        deletedCount = m_db->deleteAllMessages(roomId);
    } else if (mode == "before") {
        qint64 ts = static_cast<qint64>(data["timestamp"].toDouble());
        QDateTime dt = QDateTime::fromMSecsSinceEpoch(ts);
        fileInfos = m_db->getFileInfoBeforeTime(roomId, dt);
        deletedCount = m_db->deleteMessagesBefore(roomId, dt);
    } else if (mode == "after") {
        qint64 ts = static_cast<qint64>(data["timestamp"].toDouble());
        QDateTime dt = QDateTime::fromMSecsSinceEpoch(ts);
        fileInfos = m_db->getFileInfoAfterTime(roomId, dt);
        deletedCount = m_db->deleteMessagesAfter(roomId, dt);
    }

    // 清理文件：从 files 表删除记录 + 从磁盘删除物理文件
    if (!fileInfos.isEmpty()) {
        QList<int> fileIds;
        QJsonArray deletedFileIds;
        for (const auto &info : fileInfos) {
            fileIds.append(info.first);
            deletedFileIds.append(info.first);
            // 删除磁盘文件
            if (!info.second.isEmpty()) {
                QFile::remove(info.second);
                qInfo() << "[Server] 已删除文件:" << info.second;
            }
        }
        m_db->deleteFileRecords(fileIds);
        rspData["deletedFileIds"] = deletedFileIds;
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
    notifyData["operator"] = session->displayName();
    if (mode == "selected") {
        notifyData["messageIds"] = data["messageIds"].toArray();
    }
    // 也把删除的 fileIds 发给其他客户端
    if (rspData.contains("deletedFileIds"))
        notifyData["deletedFileIds"] = rspData["deletedFileIds"];
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_NOTIFY, notifyData), session);

    // 广播系统消息通知全体
    QString sysContent;
    if (mode == "all")
        sysContent = QString("管理员 %1 清空了所有聊天记录").arg(session->displayName());
    else if (mode == "selected")
        sysContent = QString("管理员 %1 删除了 %2 条消息").arg(session->displayName()).arg(deletedCount);
    else if (mode == "before")
        sysContent = QString("管理员 %1 删除了 %2 条旧消息").arg(session->displayName()).arg(deletedCount);
    else
        sysContent = QString("管理员 %1 删除了 %2 条近期消息").arg(session->displayName()).arg(deletedCount);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));
}

// ==================== 房间设置 ====================

// ==================== 重命名聊天室 ====================

void ChatServer::handleRenameRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString newName = data["newName"].toString().trimmed();

    QJsonObject rspData;
    rspData["roomId"] = roomId;

    if (newName.isEmpty()) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("房间名称不能为空");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_RSP, rspData));
        return;
    }

    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只有管理员可以修改房间名称");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_RSP, rspData));
        return;
    }

    QString oldName = m_db->getRoomName(roomId);
    m_db->renameRoom(roomId, newName);

    // 更新内存缓存
    m_roomMgr->renameRoom(roomId, newName);

    rspData["success"] = true;
    rspData["newName"] = newName;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_RSP, rspData));

    // 通知房间所有成员
    QJsonObject notifyData;
    notifyData["roomId"] = roomId;
    notifyData["newName"] = newName;
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_NOTIFY, notifyData));

    // 系统消息
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", QString("管理员 %1 将聊天室名称修改为 \"%2\"")
            .arg(session->displayName(), newName)}}));
}

void ChatServer::handleSetRoomPassword(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString password = data["password"].toString(); // 空字符串表示取消密码

    QJsonObject rspData;
    rspData["roomId"] = roomId;

    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只有管理员可以设置聊天室密码");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_RSP, rspData));
        return;
    }

    m_db->setRoomPassword(roomId, password);

    rspData["success"] = true;
    rspData["hasPassword"] = !password.isEmpty();
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_RSP, rspData));

    // 广播系统消息
    QString sysContent = password.isEmpty()
        ? QString("管理员 %1 已取消聊天室密码").arg(session->displayName())
        : QString("管理员 %1 已设置/修改聊天室密码").arg(session->displayName());
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));
}

void ChatServer::handleGetRoomPassword(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QJsonObject rspData;
    rspData["roomId"] = roomId;

    QString password = m_db->getRoomPassword(roomId);
    rspData["success"] = true;
    rspData["password"] = password;
    rspData["hasPassword"] = !password.isEmpty();
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::GET_ROOM_PASSWORD_RSP, rspData));
}

void ChatServer::handleKickUser(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString targetUser = data["username"].toString();

    QJsonObject rspData;
    rspData["roomId"] = roomId;
    rspData["username"] = targetUser;

    // 验证管理员权限
    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只有管理员可以踢人");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::KICK_USER_RSP, rspData));
        return;
    }

    // 查找目标用户
    int targetUserId = m_db->getUserIdByName(targetUser);
    if (targetUserId <= 0) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("用户不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::KICK_USER_RSP, rspData));
        return;
    }

    // 不能踢管理员
    if (m_db->isRoomAdmin(roomId, targetUserId)) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("不能踢出管理员");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::KICK_USER_RSP, rspData));
        return;
    }

    // 从内存和DB中移除
    m_roomMgr->removeUserFromRoom(roomId, targetUserId);
    m_db->leaveRoom(roomId, targetUserId);

    // 通知被踢用户
    QString roomName = m_db->getRoomName(roomId);
    QJsonObject kickNotify;
    kickNotify["roomId"] = roomId;
    kickNotify["roomName"] = roomName;
    kickNotify["operator"] = session->displayName();
    sendToUser(targetUser, Protocol::makeMessage(Protocol::MsgType::KICK_USER_NOTIFY, kickNotify));

    // 通知房间成员该用户被踢出
    QJsonObject leftData;
    leftData["roomId"]   = roomId;
    leftData["username"] = targetUser;
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::USER_LEFT, leftData));

    // 系统消息
    QString kickerName = session->displayName();
    QString targetName = m_db->getDisplayNameByUid(targetUser);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", QString("管理员 %1 将 %2 踢出了聊天室").arg(kickerName, targetName)}}));

    rspData["success"] = true;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::KICK_USER_RSP, rspData));
}

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
                .arg(session->displayName(), sizeStr)}}));
    } else {
        // 查询设置
        rspData["success"] = true;
        rspData["maxFileSize"] = static_cast<double>(m_db->getRoomMaxFileSize(roomId));
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
    }
}

// ==================== 删除聊天室 ====================

void ChatServer::handleDeleteRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QJsonObject rspData;
    rspData["roomId"] = roomId;

    // 检查房间是否存在
    QString roomName = m_db->getRoomName(roomId);
    if (roomName.isEmpty()) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("聊天室不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_RSP, rspData));
        return;
    }

    // 检查管理员权限
    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("您没有管理员权限");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_RSP, rspData));
        return;
    }

    // 通知房间所有在线成员（在删除前发送）
    QJsonObject notifyData;
    notifyData["roomId"] = roomId;
    notifyData["roomName"] = roomName;
    notifyData["operator"] = session->displayName();
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_NOTIFY, notifyData));

    // 从数据库删除（CASCADE 会清理 room_members, messages, files, room_admins, room_settings）
    if (m_db->deleteRoom(roomId)) {
        // 从内存缓存中移除
        m_roomMgr->removeRoom(roomId);

        rspData["success"] = true;
        rspData["roomName"] = roomName;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_RSP, rspData));
    } else {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("删除聊天室失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_RSP, rspData));
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

// ==================== 修改昵称 ====================

void ChatServer::handleChangeNickname(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString newName = data["displayName"].toString().trimmed();
    QJsonObject rspData;

    if (newName.isEmpty() || newName.length() > 20) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("昵称长度须为1-20个字符");
    } else {
        bool ok = m_db->setDisplayName(session->userId(), newName);
        if (ok) {
            session->setDisplayName(newName);
            rspData["success"]     = true;
            rspData["displayName"] = newName;

            // 通知该用户所在的所有房间
            QJsonArray rooms = m_db->getUserJoinedRooms(session->userId());
            for (const QJsonValue &v : rooms) {
                int roomId = v.toObject()["roomId"].toInt();
                QJsonObject notifyData;
                notifyData["roomId"]      = roomId;
                notifyData["username"]    = session->username();
                notifyData["displayName"] = newName;
                broadcastToRoom(roomId, Protocol::makeMessage(
                    Protocol::MsgType::NICKNAME_CHANGE_NOTIFY, notifyData));
            }
        } else {
            rspData["success"] = false;
            rspData["error"]   = QStringLiteral("修改昵称失败");
        }
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_NICKNAME_RSP, rspData));
}

void ChatServer::handleChangeUid(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString newUid = data["newUid"].toString().trimmed();
    QJsonObject rspData;

    // 验证格式
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(newUid).hasMatch()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户ID必须为6-20位，只能包含字母、数字和下划线");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));
        return;
    }

    // 检查是否与当前ID相同
    if (newUid == session->username()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("新ID与当前ID相同");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));
        return;
    }

    // 检查月度冷却
    QDateTime lastChange = m_db->getLastUidChangeTime(session->userId());
    if (lastChange.isValid()) {
        qint64 daysSince = lastChange.daysTo(QDateTime::currentDateTime());
        if (daysSince < 30) {
            int remain = 30 - static_cast<int>(daysSince);
            rspData["success"] = false;
            rspData["error"]   = QString("每月只能修改一次ID，还需等待 %1 天").arg(remain);
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));
            return;
        }
    }

    // 检查新ID是否已被占用
    int existingId = m_db->getUserIdByName(newUid);
    if (existingId > 0) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("该用户ID已被使用");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));
        return;
    }

    // 执行数据库更新
    QString oldUid = session->username();
    bool ok = m_db->changeUniqueId(session->userId(), newUid);
    if (!ok) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("修改用户ID失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));
        return;
    }

    // 更新服务端Session
    {
        QMutexLocker locker(&m_mutex);
        m_sessions.remove(oldUid);
        m_sessions[newUid] = session;
    }
    session->setUsername(newUid);

    // 更新RoomManager
    m_roomMgr->updateUsername(session->userId(), newUid);

    // 响应成功
    rspData["success"] = true;
    rspData["oldUid"]  = oldUid;
    rspData["newUid"]  = newUid;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_RSP, rspData));

    // 通知该用户所在的所有房间
    QJsonArray rooms = m_db->getUserJoinedRooms(session->userId());
    for (const QJsonValue &v : rooms) {
        int roomId = v.toObject()["roomId"].toInt();
        QJsonObject notifyData;
        notifyData["roomId"]      = roomId;
        notifyData["oldUid"]      = oldUid;
        notifyData["newUid"]      = newUid;
        notifyData["displayName"] = session->displayName();
        broadcastToRoom(roomId, Protocol::makeMessage(
            Protocol::MsgType::UID_CHANGE_NOTIFY, notifyData), session);
    }

    qInfo() << "[Server] 用户ID已修改:" << oldUid << "->" << newUid;
}

void ChatServer::handleChangePassword(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString oldPassword = data["oldPassword"].toString();
    QString newPassword = data["newPassword"].toString();
    QJsonObject rspData;

    if (newPassword.length() < 4) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("新密码至少4个字符");
    } else {
        bool ok = m_db->changePassword(session->userId(), oldPassword, newPassword);
        if (ok) {
            rspData["success"] = true;
        } else {
            rspData["success"] = false;
            rspData["error"]   = QStringLiteral("旧密码不正确");
        }
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::CHANGE_PASSWORD_RSP, rspData));
}

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

// ==================== 好友文件目录 ====================

QString ChatServer::friendFileDir(int friendshipId, const QString &fileName) const {
    QString typeDir = fileTypeSubDir(fileName);
    QString yearMonth = QDate::currentDate().toString("yyyy-MM");
    QString dir = QCoreApplication::applicationDirPath()
                  + "/server_files/friends/"
                  + QString::number(friendshipId) + "/"
                  + typeDir + "/"
                  + yearMonth;
    QDir d(dir);
    if (!d.exists()) d.mkpath(".");
    return dir;
}

// ==================== 用户搜索 ====================

void ChatServer::handleUserSearch(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString keyword = data["keyword"].toString().trimmed();
    QJsonObject rspData;

    if (keyword.isEmpty()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("搜索关键词不能为空");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_SEARCH_RSP, rspData));
        return;
    }

    QJsonArray results = m_db->searchUsers(keyword, session->userId());

    // 附加在线状态
    {
        QMutexLocker locker(&m_mutex);
        for (int i = 0; i < results.size(); ++i) {
            QJsonObject user = results[i].toObject();
            user["online"] = m_sessions.contains(user["username"].toString());
            results[i] = user;
        }
    }

    rspData["success"] = true;
    rspData["users"]   = results;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_SEARCH_RSP, rspData));
}

// ==================== 聊天室搜索 ====================

void ChatServer::handleRoomSearch(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString keyword = data["keyword"].toString().trimmed();
    QJsonObject rspData;

    if (keyword.isEmpty()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("搜索关键词不能为空");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SEARCH_RSP, rspData));
        return;
    }

    QJsonArray results = m_db->searchRooms(keyword);

    rspData["success"] = true;
    rspData["rooms"]   = results;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SEARCH_RSP, rspData));
}

// ==================== 聊天室头像 ====================

void ChatServer::handleRoomAvatarUpload(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QString avatarBase64 = data["avatarData"].toString();
    QByteArray avatarData = QByteArray::fromBase64(avatarBase64.toLatin1());

    QJsonObject rspData;
    rspData["roomId"] = roomId;

    // 检查是否是管理员
    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只有管理员可以修改聊天室头像");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_RSP, rspData));
        return;
    }

    if (avatarData.size() > 256 * 1024) { // 限制 256KB
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("头像数据过大，请选择较小的图片");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_RSP, rspData));
        return;
    }

    if (m_db->setRoomAvatar(roomId, avatarData)) {
        rspData["success"] = true;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_RSP, rspData));

        // 通知房间内所有成员头像已更新
        QJsonObject notifyData;
        notifyData["roomId"] = roomId;
        notifyData["avatarData"] = avatarBase64;
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPDATE_NOTIFY, notifyData), session);
    } else {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("保存聊天室头像失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_RSP, rspData));
    }
}

void ChatServer::handleRoomAvatarGet(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QByteArray avatarData = m_db->getRoomAvatar(roomId);

    QJsonObject rspData;
    rspData["roomId"] = roomId;
    if (!avatarData.isEmpty()) {
        rspData["success"] = true;
        rspData["avatarData"] = QString::fromLatin1(avatarData.toBase64());
    } else {
        rspData["success"] = false;
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_GET_RSP, rspData));
}

// ==================== 好友系统 ====================

void ChatServer::handleFriendRequest(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString targetUsername = data["username"].toString();
    QJsonObject rspData;

    int targetUserId = m_db->getUserIdByName(targetUsername);
    if (targetUserId < 0) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_RSP, rspData));
        return;
    }

    if (targetUserId == session->userId()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("不能添加自己为好友");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_RSP, rspData));
        return;
    }

    if (m_db->areFriends(session->userId(), targetUserId)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("已经是好友了");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_RSP, rspData));
        return;
    }

    if (!m_db->sendFriendRequest(session->userId(), targetUserId)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("已有待处理的好友请求");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_RSP, rspData));
        return;
    }

    rspData["success"] = true;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_RSP, rspData));

    // 通知对方收到好友请求
    QJsonObject notifyData;
    notifyData["fromUsername"]    = session->username();
    notifyData["fromDisplayName"] = session->displayName();
    sendToUser(targetUsername, Protocol::makeMessage(Protocol::MsgType::FRIEND_REQUEST_NOTIFY, notifyData));
}

void ChatServer::handleFriendAccept(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int requestId = data["requestId"].toInt();
    QString fromUsername = data["fromUsername"].toString(); // 客户端传递请求方用户名
    QJsonObject rspData;

    if (m_db->acceptFriendRequest(requestId, session->userId())) {
        rspData["success"] = true;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_ACCEPT_RSP, rspData));

        // 通知请求方好友已接受，让其刷新好友列表
        QJsonObject notifyData;
        notifyData["acceptedBy"]          = session->username();
        notifyData["acceptedByDisplay"]   = session->displayName();
        sendToUser(fromUsername, Protocol::makeMessage(Protocol::MsgType::FRIEND_ACCEPT_NOTIFY, notifyData));
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("处理好友请求失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_ACCEPT_RSP, rspData));
    }
}

void ChatServer::handleFriendReject(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int requestId = data["requestId"].toInt();
    QJsonObject rspData;

    if (m_db->rejectFriendRequest(requestId, session->userId())) {
        rspData["success"] = true;
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("处理好友请求失败");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REJECT_RSP, rspData));
}

void ChatServer::handleFriendRemove(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString friendUsername = data["username"].toString();
    int friendId = m_db->getUserIdByName(friendUsername);
    QJsonObject rspData;

    if (friendId > 0 && m_db->removeFriend(session->userId(), friendId)) {
        rspData["success"]  = true;
        rspData["username"] = friendUsername;

        // 通知对方刷新好友列表
        QJsonObject notifyData;
        notifyData["username"] = session->username();
        notifyData["displayName"] = session->displayName();
        sendToUser(friendUsername, Protocol::makeMessage(Protocol::MsgType::FRIEND_REMOVE_NOTIFY, notifyData));
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("删除好友失败");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REMOVE_RSP, rspData));
}

void ChatServer::handleFriendList(ClientSession *session) {
    if (!session->isAuthenticated()) return;

    QJsonArray friends = m_db->getFriendList(session->userId());

    // 添加在线状态
    {
        QMutexLocker locker(&m_mutex);
        for (int i = 0; i < friends.size(); ++i) {
            QJsonObject fr = friends[i].toObject();
            fr["isOnline"] = m_sessions.contains(fr["username"].toString());
            friends[i] = fr;
        }
    }

    QJsonObject rspData;
    rspData["friends"] = friends;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_LIST_RSP, rspData));
}

void ChatServer::handleFriendPending(ClientSession *session) {
    if (!session->isAuthenticated()) return;

    QJsonArray pending = m_db->getPendingFriendRequests(session->userId());
    QJsonObject rspData;
    rspData["requests"] = pending;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_PENDING_RSP, rspData));
}

void ChatServer::handleFriendChatMessage(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    QJsonObject data = msg["data"].toObject();
    QString friendUsername = data["friendUsername"].toString();
    QString content       = data["content"].toString();
    QString contentType   = data["contentType"].toString("text");

    int friendId = m_db->getUserIdByName(friendUsername);
    if (friendId < 0) return;

    int friendshipId = m_db->getFriendshipId(session->userId(), friendId);
    if (friendshipId < 0) return; // 不是好友

    // 保存消息
    int msgId = m_db->saveFriendMessage(friendshipId, session->userId(), content, contentType);
    if (msgId < 0) return;

    // 构建消息
    QJsonObject chatData;
    chatData["id"]           = msgId;
    chatData["friendshipId"] = friendshipId;
    chatData["sender"]       = session->username();
    chatData["senderName"]   = session->displayName();
    chatData["friendUsername"] = friendUsername;
    chatData["content"]      = content;
    chatData["contentType"]  = contentType;
    chatData["timestamp"]    = QDateTime::currentMSecsSinceEpoch();

    QJsonObject chatMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_CHAT_MSG, chatData);

    // 发送给双方
    session->sendMessage(chatMsg);
    sendToUser(friendUsername, chatMsg);
}

void ChatServer::handleFriendHistory(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString friendUsername = data["friendUsername"].toString();
    int count = data["count"].toInt(50);
    qint64 before = static_cast<qint64>(data["before"].toDouble(0));

    int friendId = m_db->getUserIdByName(friendUsername);
    if (friendId < 0) return;

    int friendshipId = m_db->getFriendshipId(session->userId(), friendId);
    if (friendshipId < 0) return;

    QJsonArray messages = m_db->getFriendMessageHistory(friendshipId, count, before);

    QJsonObject rspData;
    rspData["friendUsername"] = friendUsername;
    rspData["friendshipId"]  = friendshipId;
    rspData["messages"]      = messages;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_RSP, rspData));
}

void ChatServer::handleFriendFileSend(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    QJsonObject data = msg["data"].toObject();
    QString friendUsername = data["friendUsername"].toString();
    QString fileName  = data["fileName"].toString();
    qint64 fileSize   = static_cast<qint64>(data["fileSize"].toDouble());
    QString fileData  = data["fileData"].toString();
    QString contentType = data["contentType"].toString("file");
    QString thumbnail = data["thumbnail"].toString();

    int friendId = m_db->getUserIdByName(friendUsername);
    if (friendId < 0) return;
    int friendshipId = m_db->getFriendshipId(session->userId(), friendId);
    if (friendshipId < 0) return;

    // 好友文件上限 10GB
    if (fileSize > Protocol::MAX_FRIEND_FILE) {
        QJsonObject rsp;
        rsp["success"] = false;
        rsp["error"] = QStringLiteral("文件超过好友传输限制(10GB)");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, rsp));
        return;
    }

    // 保存文件
    QByteArray rawData = QByteArray::fromBase64(fileData.toLatin1());
    QString targetDir = friendFileDir(friendshipId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    QFile f(filePath);
    if (!f.open(QIODevice::WriteOnly)) return;
    f.write(rawData);
    f.close();

    int fileId = m_db->saveFriendFile(friendshipId, session->userId(), fileName, filePath, fileSize);
    int msgId  = m_db->saveFriendMessage(friendshipId, session->userId(), fileName, contentType,
                                          fileName, fileSize, fileId, thumbnail);

    QJsonObject notifyData;
    notifyData["id"]           = msgId;
    notifyData["friendshipId"] = friendshipId;
    notifyData["sender"]       = session->username();
    notifyData["senderName"]   = session->displayName();
    notifyData["friendUsername"] = friendUsername;
    notifyData["content"]      = fileName;
    notifyData["contentType"]  = contentType;
    notifyData["fileName"]     = fileName;
    notifyData["fileSize"]     = static_cast<double>(fileSize);
    notifyData["fileId"]       = -fileId;  // 负数标识好友文件
    notifyData["timestamp"]    = QDateTime::currentMSecsSinceEpoch();
    if (!thumbnail.isEmpty()) notifyData["thumbnail"] = thumbnail;

    QJsonObject notifyMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, notifyData);
    session->sendMessage(notifyMsg);
    sendToUser(friendUsername, notifyMsg);
}

void ChatServer::handleFriendFileUploadStart(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString friendUsername = data["friendUsername"].toString();
    QString fileName = data["fileName"].toString();
    qint64 fileSize  = static_cast<qint64>(data["fileSize"].toDouble());

    int friendId = m_db->getUserIdByName(friendUsername);
    QJsonObject rspData;

    if (friendId < 0) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户不存在");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    int friendshipId = m_db->getFriendshipId(session->userId(), friendId);
    if (friendshipId < 0) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("不是好友关系");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    // 好友文件上限 10GB
    if (fileSize > Protocol::MAX_FRIEND_FILE) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件超过好友传输限制(10GB)");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    QString uploadId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    QString targetDir = friendFileDir(friendshipId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    auto *file = new QFile(filePath);
    if (!file->open(QIODevice::WriteOnly)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("服务器无法创建文件");
        delete file;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    UploadState state;
    state.roomId      = -friendshipId; // 用负数标识好友文件上传
    state.userId      = session->userId();
    state.username    = session->username();
    state.displayName = session->displayName();
    state.fileName    = fileName;
    state.filePath    = filePath;
    state.fileSize    = fileSize;
    state.received    = 0;
    state.file        = file;
    m_uploads[uploadId] = state;

    rspData["success"]        = true;
    rspData["uploadId"]       = uploadId;
    rspData["friendUsername"]  = friendUsername;
    rspData["friendshipId"]   = friendshipId;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
}

void ChatServer::handleFriendRecall(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int messageId = data["messageId"].toInt();
    QString friendUsername = data["friendUsername"].toString();

    QJsonObject rspData;
    rspData["messageId"] = messageId;
    rspData["friendUsername"] = friendUsername;

    bool ok = m_db->recallFriendMessage(messageId, session->userId(), Protocol::RECALL_TIME_LIMIT_SEC);
    if (ok) {
        // 清理服务器文件
        auto fileInfo = m_db->getFileInfoForFriendMessage(messageId);
        if (fileInfo.first > 0 && !fileInfo.second.isEmpty()) {
            QFile::remove(fileInfo.second);
            qInfo() << "[Server] 好友撤回消息，已删除文件:" << fileInfo.second;
        }

        rspData["success"] = true;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_RSP, rspData));

        // 通知对方
        QMutexLocker locker(&m_mutex);
        if (m_sessions.contains(friendUsername)) {
            QJsonObject notifyData;
            notifyData["messageId"] = messageId;
            notifyData["friendUsername"] = session->username();
            m_sessions[friendUsername]->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_NOTIFY, notifyData));
        }
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("无法撤回（超时或非本人消息）");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_RSP, rspData));
    }
}
