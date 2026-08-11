#include "ChatServer.h"
#include "ClientSession.h"
#include "DatabaseManager.h"
#include "RoomManager.h"
#include "CosManager.h"
#include "Protocol.h"
#include "Message.h"
#include "InputValidator.h"
#include "RoomMessageService.h"
#include "AdministrativeDeletionService.h"

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
#ifndef CHATROOM_DISABLE_IMAGE_THUMBNAILS
#include <QImage>
#include <QBuffer>
#endif
#include <QTcpSocket>
#include <QUrl>
#include <QUrlQuery>
#include <QFileInfo>
#include <QMimeDatabase>
#include <QDateTime>
#include <QUuid>
#include <QTimer>
#ifdef CHATROOM_ENABLE_BENCHMARK_METRICS
#include <QElapsedTimer>
#endif
#include <QTextStream>
#include <QStringList>
#include <cmath>
#include <limits>

namespace {

constexpr int kMaxClientMessageIdBytes = 128;

bool validOptionalClientMessageId(const QString &clientMessageId) {
    return clientMessageId.isEmpty() ||
           clientMessageId.toUtf8().size() <= kMaxClientMessageIdBytes;
}

QString readEnvValueFromFile(const QString &filePath, const QString &key) {
    QFile envFile(filePath);
    if (!envFile.open(QIODevice::ReadOnly | QIODevice::Text)) {
        return QString();
    }

    QTextStream stream(&envFile);
    while (!stream.atEnd()) {
        QString line = stream.readLine().trimmed();
        if (line.isEmpty() || line.startsWith('#')) {
            continue;
        }
        if (line.startsWith(QStringLiteral("export "))) {
            line = line.mid(7).trimmed();
        }

        const int equalPos = line.indexOf('=');
        if (equalPos <= 0) {
            continue;
        }

        const QString envKey = line.left(equalPos).trimmed();
        if (envKey != key) {
            continue;
        }

        QString value = line.mid(equalPos + 1).trimmed();
        if ((value.startsWith('"') && value.endsWith('"')) ||
            (value.startsWith('\'') && value.endsWith('\''))) {
            value = value.mid(1, value.size() - 2);
        }
        return value;
    }

    return QString();
}

QString buildForwardThumbnail(const QString &filePath, const QString &fileName,
                              qint64 fileSize) {
#ifndef CHATROOM_DISABLE_IMAGE_THUMBNAILS
    static const QStringList imageExtensions = {
        "png", "jpg", "jpeg", "gif", "bmp", "webp"
    };
    if (fileSize < 20 * 1024 * 1024 &&
        imageExtensions.contains(QFileInfo(fileName).suffix().toLower())) {
        QImage image(filePath);
        if (!image.isNull()) {
            const QImage thumbnail = image.scaled(
                200, 200, Qt::KeepAspectRatio, Qt::FastTransformation);
            QByteArray bytes;
            QBuffer buffer(&bytes);
            buffer.open(QIODevice::WriteOnly);
            thumbnail.save(&buffer, "JPEG", 60);
            return QString::fromLatin1(bytes.toBase64());
        }
    }
#else
    Q_UNUSED(filePath)
    Q_UNUSED(fileName)
    Q_UNUSED(fileSize)
#endif
    return {};
}

} // namespace

ChatServer::ChatServer(QObject *parent)
    : QTcpServer(parent),
      m_db(new DatabaseManager(this)),
      m_roomMgr(new RoomManager(this)),
      m_cos(new CosManager(this)),
      m_roomMessageService(m_db),
      m_friendMessageService(m_db),
      m_administrativeDeletionService(m_db) {}

ChatServer::~ChatServer() {
    stopServer();
}

bool ChatServer::startServer(quint16 port, quint16 wsPort, quint16 httpPort) {
    m_authAbuseGuard.reset();
    m_roomMessagesAccepted = 0;
    m_roomMessagesDuplicate = 0;
    m_roomMessagesRejected = 0;
    m_friendMessagesAccepted = 0;
    m_friendMessagesDuplicate = 0;
    m_friendMessagesRejected = 0;
    m_administrativeDeletionsAccepted = 0;
    m_administrativeDeletionsDuplicate = 0;
    m_administrativeDeletionsRejected = 0;
    const AuthenticationAbuseGuard::Limits authLimits = m_authAbuseGuard.limits();
    qInfo().noquote()
        << QStringLiteral("[AuthAbuse] configured windowMs=%1 gatewayLimit=%2 ipLimit=%3 accountLimit=%4 maxTrackedKeys=%5")
               .arg(authLimits.windowMs)
               .arg(authLimits.gatewayAttempts)
               .arg(authLimits.ipAttempts)
               .arg(authLimits.accountAttempts)
               .arg(authLimits.maxTrackedKeys);

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

    // 启动 HTTP 下载服务（默认 TCP 端口 + 2）
    if (httpPort == 0) httpPort = port + 2;
    m_httpPort = httpPort;
    if (!setupHttpServer(httpPort)) {
        return false;
    }

    // 加载 COS 配置（需在 expireStoredFiles 前加载，以便 deleteCosFiles 可用）
    m_cos->loadConfig();

    deleteCosFiles(m_db->expireStoredFiles());

    if (!m_expireTimer) {
        m_expireTimer = new QTimer(this);
        m_expireTimer->setInterval(60 * 60 * 1000);
        connect(m_expireTimer, &QTimer::timeout, this, [this] {
            deleteCosFiles(m_db->expireStoredFiles());
        });
    }
    m_expireTimer->start();
    return true;
}

void ChatServer::stopServer() {
    close();
    if (m_wsServer) {
        m_wsServer->close();
    }
    if (m_httpServer) {
        m_httpServer->close();
        m_httpServer->deleteLater();
        m_httpServer = nullptr;
    }
    if (m_expireTimer) {
        m_expireTimer->stop();
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
    qInfo() << "[Server] 用户认证成功, userId:" << session->userId();

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
        if (state.roomQuotaReserved)
            releaseRoomFileQuota(state.roomId, state.fileSize);
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
    } else if (type == Protocol::MsgType::FILE_FORWARD_REQ) {
        handleFileForward(session, msg["data"].toObject());
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
        handleDeleteMessages(session, msg);
    } else if (type == Protocol::MsgType::ROOM_SETTINGS_REQ) {
        handleRoomSettings(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_FILES_REQ) {
        handleRoomFiles(session, msg["data"].toObject());
    } else if (type == Protocol::MsgType::ROOM_FILES_DELETE_REQ) {
        handleRoomFilesDelete(session, msg);
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
    } else if (type == Protocol::MsgType::MARK_ROOM_READ) {
        if (session->isAuthenticated()) {
            int roomId = msg["data"].toObject()["roomId"].toInt();
            if (requireRoomMembership(session, roomId, QStringLiteral("room-mark-read")))
                m_db->markRoomRead(roomId, session->userId());
        }
    } else if (type == Protocol::MsgType::MARK_FRIEND_READ) {
        if (session->isAuthenticated()) {
            int friendshipId = msg["data"].toObject()["friendshipId"].toInt();
            if (m_db->isUserInFriendship(friendshipId, session->userId())) {
                m_db->markFriendRead(friendshipId, session->userId());
            } else {
                qWarning().noquote() << QStringLiteral("[Authz] denied operation=friend-mark-read userId=%1")
                                            .arg(session->userId());
            }
        }
    } else if (type == Protocol::MsgType::HEARTBEAT) {
        session->sendMessage(Protocol::makeHeartbeatAck());
    }
}

// ==================== 认证处理 ====================

void ChatServer::handleLogin(ClientSession *session, const QJsonObject &data) {
    QString username = data["username"].toString();
    QString password = data["password"].toString();
    QString validationError;
    if (username.isEmpty() || username.size() > InputValidator::MAX_USERNAME_CHARS
        || !InputValidator::validatePassword(password, &validationError, false)) {
        QJsonObject rspData;
        rspData["success"] = false;
        rspData["error"] = validationError.isEmpty()
            ? QStringLiteral("用户ID或密码格式无效") : validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LOGIN_RSP, rspData));
        return;
    }
    if (!allowAuthenticationAttempt(session, username, QStringLiteral("login"),
                                    Protocol::MsgType::LOGIN_RSP)) {
        return;
    }

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
        rspData["fileToken"]   = generateFileToken(userId);
        rspData["httpPort"]    = m_httpPort;
        rspData["serverFileForward"] = true;
        m_authAbuseGuard.recordSuccess(username);
        emit session->authenticated(session);
    } else {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户ID或密码错误");
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LOGIN_RSP, rspData));
}


QString ChatServer::generateFileToken(int userId) {
    // 清理该用户旧 token，避免并存过多历史令牌。
    for (auto it = m_fileTokens.begin(); it != m_fileTokens.end(); ) {
        if (it.value().first == userId) {
            it = m_fileTokens.erase(it);
        } else {
            ++it;
        }
    }

    const QString token = QUuid::createUuid().toString(QUuid::WithoutBraces);
    const QDateTime expireAt = QDateTime::currentDateTimeUtc().addSecs(24 * 60 * 60);
    m_fileTokens[token] = qMakePair(userId, expireAt);
    return token;
}

int ChatServer::validateFileToken(const QString &token) const {
    if (token.isEmpty()) return 0;
    auto it = m_fileTokens.constFind(token);
    if (it == m_fileTokens.constEnd()) return 0;
    if (it.value().second < QDateTime::currentDateTimeUtc()) return 0;
    return it.value().first;
}

bool ChatServer::requireRoomMembership(ClientSession *session, int roomId,
                                       const QString &operation) const {
    const int userId = session && session->isAuthenticated() ? session->userId() : 0;
    if (userId > 0 && roomId > 0 && m_db->isUserInRoom(roomId, userId)) return true;

    qWarning().noquote() << QStringLiteral("[Authz] denied operation=%1 userId=%2 roomId=%3")
                                .arg(operation)
                                .arg(userId)
                                .arg(roomId);
    return false;
}

void ChatServer::recordRoomMessageOutcome(RoomMessageService::Status status,
                                          int userId, int roomId) {
    QString outcome;
    if (status == RoomMessageService::Status::Accepted) {
        ++m_roomMessagesAccepted;
        outcome = QStringLiteral("accepted");
    } else if (status == RoomMessageService::Status::Duplicate) {
        ++m_roomMessagesDuplicate;
        outcome = QStringLiteral("duplicate");
    } else {
        ++m_roomMessagesRejected;
        outcome = QStringLiteral("rejected");
    }

    const quint64 total = m_roomMessagesAccepted + m_roomMessagesDuplicate +
                          m_roomMessagesRejected;
    if (total == 1 || (total & (total - 1)) == 0 ||
        status != RoomMessageService::Status::Accepted) {
        qInfo().noquote()
            << QStringLiteral("[Messaging] room-send outcome=%1 userId=%2 roomId=%3 acceptedTotal=%4 duplicateTotal=%5 rejectedTotal=%6")
                   .arg(outcome)
                   .arg(userId)
                   .arg(roomId)
                   .arg(m_roomMessagesAccepted)
                   .arg(m_roomMessagesDuplicate)
                   .arg(m_roomMessagesRejected);
    }
}

void ChatServer::recordFriendMessageOutcome(FriendMessageService::Status status,
                                            int userId, int friendshipId) {
    QString outcome;
    if (status == FriendMessageService::Status::Accepted) {
        ++m_friendMessagesAccepted;
        outcome = QStringLiteral("accepted");
    } else if (status == FriendMessageService::Status::Duplicate) {
        ++m_friendMessagesDuplicate;
        outcome = QStringLiteral("duplicate");
    } else {
        ++m_friendMessagesRejected;
        outcome = QStringLiteral("rejected");
    }

    const quint64 total = m_friendMessagesAccepted + m_friendMessagesDuplicate +
                          m_friendMessagesRejected;
    if (total == 1 || (total & (total - 1)) == 0 ||
        status != FriendMessageService::Status::Accepted) {
        qInfo().noquote()
            << QStringLiteral("[Messaging] friend-send outcome=%1 userId=%2 friendshipId=%3 acceptedTotal=%4 duplicateTotal=%5 rejectedTotal=%6")
                   .arg(outcome)
                   .arg(userId)
                   .arg(friendshipId)
                   .arg(m_friendMessagesAccepted)
                   .arg(m_friendMessagesDuplicate)
                   .arg(m_friendMessagesRejected);
    }
}

void ChatServer::recordAdministrativeDeletionOutcome(
    AdministrativeDeletionService::Status status, int userId, int roomId,
    qint64 sequence, const QString &clientOperationId) {
    QString outcome;
    if (status == AdministrativeDeletionService::Status::Accepted) {
        ++m_administrativeDeletionsAccepted;
        outcome = QStringLiteral("accepted");
    } else if (status == AdministrativeDeletionService::Status::Duplicate) {
        ++m_administrativeDeletionsDuplicate;
        outcome = QStringLiteral("duplicate");
    } else {
        ++m_administrativeDeletionsRejected;
        outcome = QStringLiteral("rejected");
    }
    qInfo().noquote()
        << QStringLiteral("[AdminDelete] outcome=%1 userId=%2 roomId=%3 sequence=%4 operationId=%5 acceptedTotal=%6 duplicateTotal=%7 rejectedTotal=%8")
               .arg(outcome)
               .arg(userId)
               .arg(roomId)
               .arg(sequence)
               .arg(clientOperationId)
               .arg(m_administrativeDeletionsAccepted)
               .arg(m_administrativeDeletionsDuplicate)
               .arg(m_administrativeDeletionsRejected);
}

bool ChatServer::requireUploadOwnership(ClientSession *session, const QString &uploadId,
                                        QJsonObject *response) const {
    const int userId = session && session->isAuthenticated() ? session->userId() : 0;
    const auto it = m_uploads.constFind(uploadId);
    const bool allowed = userId > 0 && it != m_uploads.constEnd()
                         && it.value().userId == userId;
    if (allowed) return true;

    if (response) {
        (*response)["uploadId"] = uploadId;
        (*response)["success"] = false;
        (*response)["error"] = QStringLiteral("无权操作该上传");
    }
    qWarning().noquote() << QStringLiteral("[Authz] denied operation=upload-owner userId=%1")
                                .arg(userId);
    return false;
}

void ChatServer::sendUploadFinalizeResponse(
    ClientSession *session, const QString &uploadId,
    const QString &clientMessageId, const MessageSaveResult &result,
    bool isFriendFile, const QString &errorCode, const QString &error) {
    if (!session) return;

    const bool success = result.status == MessageSaveResult::Status::Created ||
                         result.status == MessageSaveResult::Status::Duplicate;
    QJsonObject response;
    response["success"] = success;
    response["uploadId"] = uploadId;
    if (!clientMessageId.isEmpty()) response["clientMessageId"] = clientMessageId;
    if (success) {
        response["id"] = result.messageId;
        response["fileId"] = isFriendFile ? -result.fileId : result.fileId;
        response["sequence"] = static_cast<double>(result.sequence);
        response["timestamp"] = static_cast<double>(result.createdAtMs);
        response["duplicate"] = result.status == MessageSaveResult::Status::Duplicate;
    } else {
        response["errorCode"] = errorCode.isEmpty()
            ? QStringLiteral("FINALIZE_PERSIST_FAILED") : errorCode;
        response["error"] = error.isEmpty()
            ? QStringLiteral("服务器无法确认文件消息") : error;
    }
    session->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_END_RSP, response));

    QString outcome;
    if (result.status == MessageSaveResult::Status::Created) {
        ++m_attachmentFinalizationsAccepted;
        outcome = QStringLiteral("accepted");
    } else if (result.status == MessageSaveResult::Status::Duplicate) {
        ++m_attachmentFinalizationsDuplicate;
        outcome = QStringLiteral("duplicate");
    } else {
        ++m_attachmentFinalizationsRejected;
        outcome = QStringLiteral("rejected");
    }
    qInfo().noquote()
        << QStringLiteral("[AttachmentFinalize] outcome=%1 userId=%2 uploadId=%3 acceptedTotal=%4 duplicateTotal=%5 rejectedTotal=%6")
               .arg(outcome)
               .arg(session->userId())
               .arg(uploadId)
               .arg(m_attachmentFinalizationsAccepted)
               .arg(m_attachmentFinalizationsDuplicate)
               .arg(m_attachmentFinalizationsRejected);
}

bool ChatServer::allowAuthenticationAttempt(ClientSession *session,
                                            const QString &account,
                                            const QString &operation,
                                            const QString &responseType) {
    const AuthenticationAbuseGuard::Decision decision =
        m_authAbuseGuard.allow(session ? session->peerAddress() : QString(), account);
    if (decision.allowed) return true;

    QJsonObject rspData;
    rspData["success"] = false;
    rspData["error"] = QStringLiteral("认证请求过于频繁，请稍后重试");
    if (session) {
        session->sendMessage(Protocol::makeMessage(responseType, rspData));
    }

    const quint64 dimensionDenied = decision.dimensionDeniedAttempts;
    const bool shouldLog = dimensionDenied == 1
        || (dimensionDenied > 0 && (dimensionDenied & (dimensionDenied - 1)) == 0);
    if (shouldLog) {
        qWarning().noquote()
            << QStringLiteral("[AuthAbuse] denied operation=%1 dimension=%2 retryAfterMs=%3 dimensionDenied=%4 totalAllowed=%5 totalDenied=%6 activeIpKeys=%7 activeAccountKeys=%8")
                   .arg(operation, decision.dimension)
                   .arg(decision.retryAfterMs)
                   .arg(decision.dimensionDeniedAttempts)
                   .arg(decision.allowedAttempts)
                   .arg(decision.deniedAttempts)
                   .arg(decision.activeIpKeys)
                   .arg(decision.activeAccountKeys);
    }
    return false;
}

bool ChatServer::validateDeveloperKey(const QString &providedKey, QString *error) const {
    QString expectedKey = qEnvironmentVariable("CHATROOM_DEVELOPER_KEY").trimmed();
    if (expectedKey.isEmpty()) {
        const QString appDir = QCoreApplication::applicationDirPath();
        const QString envPathFromVar = qEnvironmentVariable("CHATROOM_ENV_FILE").trimmed();

        QStringList candidates;
        if (!envPathFromVar.isEmpty()) {
            candidates << envPathFromVar;
        }
        candidates << QDir::current().filePath(".env")
                   << QDir(appDir).filePath(".env")
                   << QDir(appDir).filePath("../.env");

        for (const QString &path : candidates) {
            const QFileInfo info(path);
            if (!info.exists() || !info.isFile()) {
                continue;
            }

            expectedKey = readEnvValueFromFile(info.absoluteFilePath(), QStringLiteral("CHATROOM_DEVELOPER_KEY")).trimmed();
            if (!expectedKey.isEmpty()) {
                break;
            }
        }
    }

    const QString inputKey = providedKey.trimmed();

    if (expectedKey.isEmpty()) {
        if (error) {
            *error = QStringLiteral("开发者秘钥未配置（请在 .env 中设置 CHATROOM_DEVELOPER_KEY）");
        }
        return false;
    }

    if (inputKey != expectedKey) {
        if (error) {
            *error = QStringLiteral("开发者秘钥错误");
        }
        return false;
    }

    return true;
}

bool ChatServer::setupHttpServer(quint16 port) {
    if (m_httpServer) {
        m_httpServer->close();
        m_httpServer->deleteLater();
        m_httpServer = nullptr;
    }

    m_httpServer = new QTcpServer(this);
    connect(m_httpServer, &QTcpServer::newConnection, this, [this]() {
        while (m_httpServer->hasPendingConnections()) {
            QTcpSocket *socket = m_httpServer->nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, this, [this, socket]() {
                handleHttpRequest(socket);
            });
            connect(socket, &QTcpSocket::disconnected, this, [this, socket]() {
                if (socket->property("rawUploadActive").toBool())
                    abandonUpload(socket->property("rawUploadId").toString());
            });
            connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
        }
    });

    if (!m_httpServer->listen(QHostAddress::Any, port)) {
        qCritical() << "[Server] HTTP 监听端口失败:" << port << m_httpServer->errorString();
        return false;
    }
    qInfo() << "[Server] HTTP 下载服务已启动，监听端口:" << port;
    return true;
}

void ChatServer::handleHttpRequest(QTcpSocket *socket) {
    if (!socket) return;

    auto writeSimple = [socket](int status, const QByteArray &statusText, const QByteArray &body = QByteArray()) {
        QByteArray resp;
        resp += "HTTP/1.1 " + QByteArray::number(status) + " " + statusText + "\r\n";
        resp += "Access-Control-Allow-Origin: *\r\n";
        resp += "Access-Control-Allow-Methods: GET, PUT, OPTIONS\r\n";
        resp += "Access-Control-Allow-Headers: Content-Type, Content-Length\r\n";
        if (!body.isEmpty()) {
            resp += "Content-Type: text/plain; charset=utf-8\r\n";
            resp += "Content-Length: " + QByteArray::number(body.size()) + "\r\n";
        } else {
            resp += "Content-Length: 0\r\n";
        }
        resp += "Connection: close\r\n\r\n";
        if (!body.isEmpty()) resp += body;
        socket->write(resp);
        socket->disconnectFromHost();
    };

    if (socket->property("rawUploadActive").toBool()) {
        const QString uploadId = socket->property("rawUploadId").toString();
        const qint64 expected = socket->property("rawUploadLength").toLongLong();
        qint64 received = socket->property("rawUploadReceived").toLongLong();
        auto it = m_uploads.find(uploadId);
        if (it == m_uploads.end() || !it->file || !it->file->isOpen()) {
            socket->setProperty("rawUploadActive", false);
            writeSimple(404, "Not Found", "Unknown upload");
            return;
        }
        while (socket->bytesAvailable() > 0 && received < expected) {
            const qint64 wanted = qMin<qint64>(64 * 1024, expected - received);
            const QByteArray chunk = socket->read(wanted);
            if (chunk.isEmpty()) break;
            if (it->file->write(chunk) != chunk.size()) {
                socket->setProperty("rawUploadActive", false);
                abandonUpload(uploadId);
                writeSimple(500, "Internal Server Error", "Write failed");
                return;
            }
            received += chunk.size();
            it->received = received;
            socket->setProperty("rawUploadReceived", received);
        }
        if (received == expected) {
            if (socket->bytesAvailable() > 0) {
                socket->setProperty("rawUploadActive", false);
                abandonUpload(uploadId);
                writeSimple(400, "Bad Request", "Body exceeds Content-Length");
                return;
            }
            it->file->flush();
            socket->setProperty("rawUploadActive", false);
            writeSimple(204, "No Content");
        }
        return;
    }

    QByteArray req = socket->property("httpHeader").toByteArray();
    if (req.size() >= 16 * 1024) {
        writeSimple(431, "Request Header Fields Too Large");
        return;
    }
    req += socket->read(16 * 1024 - req.size());
    const int headerEnd = req.indexOf("\r\n\r\n");
    if (headerEnd < 0) {
        socket->setProperty("httpHeader", req);
        return;
    }
    const QByteArray initialBody = req.mid(headerEnd + 4);
    socket->setProperty("httpHeader", QByteArray());
    req.truncate(headerEnd + 4);

    const QList<QByteArray> lines = req.split('\n');
    if (lines.isEmpty()) {
        socket->disconnectFromHost();
        return;
    }

    const QByteArray requestLine = lines.first().trimmed();
    const QList<QByteArray> parts = requestLine.split(' ');
    if (parts.size() < 2) {
        socket->disconnectFromHost();
        return;
    }

    const QByteArray method = parts[0];
    const QString target = QString::fromUtf8(parts[1]);

    if (method == "OPTIONS") {
        writeSimple(204, "No Content");
        return;
    }
    const QUrl url(target);
    const QString path = url.path();
    if (method == "PUT") {
        static const QRegularExpression uploadRe(
            QStringLiteral("^/api/upload/([A-Za-z0-9-]{1,128})$"));
        const QRegularExpressionMatch uploadMatch = uploadRe.match(path);
        if (!uploadMatch.hasMatch()) {
            writeSimple(404, "Not Found", "Not Found");
            return;
        }
        qint64 contentLength = -1;
        for (const QByteArray &line : lines) {
            const int separator = line.indexOf(':');
            if (separator <= 0) continue;
            if (line.left(separator).trimmed().compare("Content-Length", Qt::CaseInsensitive) == 0) {
                bool ok = false;
                contentLength = line.mid(separator + 1).trimmed().toLongLong(&ok);
                if (!ok) contentLength = -1;
            }
            if (line.left(separator).trimmed().compare("Transfer-Encoding", Qt::CaseInsensitive) == 0) {
                writeSimple(400, "Bad Request", "Chunked transfer is not supported");
                return;
            }
        }
        const QString uploadId = uploadMatch.captured(1);
        const QUrlQuery query(url);
        const int tokenUserId = validateFileToken(query.queryItemValue(QStringLiteral("token")));
        auto it = m_uploads.find(uploadId);
        if (tokenUserId <= 0) {
            writeSimple(401, "Unauthorized", "Invalid token");
            return;
        }
        if (it == m_uploads.end() || it->userId != tokenUserId) {
            qWarning().noquote() << QStringLiteral("[Authz] denied operation=http-file-upload userId=%1")
                                        .arg(tokenUserId);
            writeSimple(403, "Forbidden", "Forbidden");
            return;
        }
        if (contentLength != it->fileSize || it->received != 0) {
            writeSimple(400, "Bad Request", "Content-Length mismatch or upload already started");
            return;
        }
        socket->setProperty("rawUploadActive", true);
        socket->setProperty("rawUploadId", uploadId);
        socket->setProperty("rawUploadLength", contentLength);
        socket->setProperty("rawUploadReceived", 0);
        if (!initialBody.isEmpty()) {
            if (initialBody.size() > contentLength ||
                it->file->write(initialBody) != initialBody.size()) {
                socket->setProperty("rawUploadActive", false);
                abandonUpload(uploadId);
                writeSimple(400, "Bad Request", "Invalid upload body");
                return;
            }
            it->received = initialBody.size();
            socket->setProperty("rawUploadReceived", initialBody.size());
        }
        handleHttpRequest(socket);
        return;
    }
    if (method != "GET") {
        writeSimple(405, "Method Not Allowed", "Method Not Allowed");
        return;
    }

    static const QRegularExpression re(QStringLiteral("^/api/download/(-?\\d+)$"));
    const QRegularExpressionMatch match = re.match(path);
    if (!match.hasMatch()) {
        writeSimple(404, "Not Found", "Not Found");
        return;
    }

    const int fileIdRaw = match.captured(1).toInt();
    const QUrlQuery query(url);
    const QString token = query.queryItemValue(QStringLiteral("token"));
    const int tokenUserId = validateFileToken(token);
    if (tokenUserId <= 0) {
        writeSimple(401, "Unauthorized", "Invalid token");
        return;
    }

    const bool forceFriend = (query.queryItemValue(QStringLiteral("friend")) == QStringLiteral("1"));
    const QString disposition = query.queryItemValue(QStringLiteral("disposition")).toLower();
    const bool asInline = (disposition == QStringLiteral("inline"));
    const bool isFriendFile = forceFriend || (fileIdRaw < 0);
    const int dbFileId = (fileIdRaw < 0) ? -fileIdRaw : fileIdRaw;

    if (!m_db->canUserAccessFile(dbFileId, isFriendFile, tokenUserId)) {
        qWarning().noquote() << QStringLiteral("[Authz] denied operation=http-file-download userId=%1 fileId=%2 friend=%3")
                                    .arg(tokenUserId)
                                    .arg(dbFileId)
                                    .arg(isFriendFile);
        writeSimple(403, "Forbidden", "Forbidden");
        return;
    }

    // 如果 COS 已启用且文件已上传到 COS，返回 302 重定向
    if (m_cos->isEnabled()) {
        const QString cosUrl = m_db->getCosUrl(dbFileId, isFriendFile);
        if (!cosUrl.isEmpty()) {
            const QString signedUrl = m_cos->presignedUrl(cosUrl);
            QByteArray resp;
            resp += "HTTP/1.1 302 Found\r\n";
            resp += "Access-Control-Allow-Origin: *\r\n";
            resp += "Location: " + signedUrl.toUtf8() + "\r\n";
            resp += "Connection: close\r\n\r\n";
            socket->write(resp);
            socket->disconnectFromHost();
            return;
        }
    }

    const QString filePath = m_db->getFilePath(dbFileId, isFriendFile);
    const QString fileName = m_db->getFileName(dbFileId, isFriendFile);
    QFile *file = new QFile(filePath, socket);
    if (filePath.isEmpty() || !file->exists() || !file->open(QIODevice::ReadOnly)) {
        file->deleteLater();
        writeSimple(404, "Not Found", "File not found");
        return;
    }

    const QMimeDatabase db;
    const QMimeType mime = db.mimeTypeForFile(fileName, QMimeDatabase::MatchExtension);
    const QByteArray mimeType = mime.isValid() ? mime.name().toUtf8() : QByteArray("application/octet-stream");
    const QByteArray encodedName = QUrl::toPercentEncoding(fileName);
    const QByteArray safeName = fileName.toUtf8().replace('"', '_');

    // 解析 Range 请求头
    const qint64 totalSize = file->size();
    qint64 rangeStart = 0;
    qint64 rangeEnd = totalSize - 1;
    bool hasRange = false;

    for (int i = 1; i < lines.size(); ++i) {
        const QByteArray line = lines[i].trimmed();
        if (line.startsWith("Range:")) {
            const QByteArray rangeValue = line.mid(6).trimmed();
            if (rangeValue.startsWith("bytes=")) {
                const QString rangeSpec = QString::fromUtf8(rangeValue.mid(6));
                const int dashIdx = rangeSpec.indexOf(QLatin1Char('-'));
                if (dashIdx >= 0) {
                    const QString startStr = rangeSpec.left(dashIdx).trimmed();
                    const QString endStr = rangeSpec.mid(dashIdx + 1).trimmed();
                    bool okStart = false, okEnd = false;
                    if (!startStr.isEmpty()) {
                        const qint64 s = startStr.toLongLong(&okStart);
                        if (okStart && s >= 0 && s < totalSize) {
                            rangeStart = s;
                            hasRange = true;
                            if (!endStr.isEmpty()) {
                                const qint64 e = endStr.toLongLong(&okEnd);
                                if (okEnd && e >= rangeStart && e < totalSize)
                                    rangeEnd = e;
                                else
                                    rangeEnd = totalSize - 1;
                            }
                        }
                    } else if (!endStr.isEmpty()) {
                        // suffix range, e.g. bytes=-500
                        const qint64 suffix = endStr.toLongLong(&okEnd);
                        if (okEnd && suffix > 0 && suffix <= totalSize) {
                            rangeStart = totalSize - suffix;
                            hasRange = true;
                        }
                    }
                }
            }
            break;
        }
    }

    if (hasRange && (rangeStart > rangeEnd || rangeStart >= totalSize)) {
        QByteArray resp;
        resp += "HTTP/1.1 416 Range Not Satisfiable\r\n";
        resp += "Content-Range: bytes */" + QByteArray::number(totalSize) + "\r\n";
        resp += "Connection: close\r\n\r\n";
        socket->write(resp);
        file->close();
        file->deleteLater();
        socket->disconnectFromHost();
        return;
    }

    const qint64 contentLength = rangeEnd - rangeStart + 1;

    QByteArray headers;
    if (hasRange) {
        headers += "HTTP/1.1 206 Partial Content\r\n";
    } else {
        headers += "HTTP/1.1 200 OK\r\n";
    }
    headers += "Access-Control-Allow-Origin: *\r\n";
    headers += "Access-Control-Allow-Methods: GET, OPTIONS\r\n";
    headers += "Access-Control-Allow-Headers: Content-Type, Range\r\n";
    headers += "Accept-Ranges: bytes\r\n";
    headers += "Content-Type: " + mimeType + "\r\n";
    headers += "Content-Length: " + QByteArray::number(contentLength) + "\r\n";
    if (hasRange) {
        headers += "Content-Range: bytes " + QByteArray::number(rangeStart) + "-"
                   + QByteArray::number(rangeEnd) + "/" + QByteArray::number(totalSize) + "\r\n";
    }
    headers += "Content-Disposition: " + QByteArray(asInline ? "inline" : "attachment")
               + "; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName + "\r\n";
    headers += "Connection: close\r\n\r\n";
    socket->write(headers);

    if (hasRange) {
        file->seek(rangeStart);
    }

    // 非阻塞分段发送，避免大文件传输阻塞事件循环，影响并发下载请求处理。
    qint64 *remaining = new qint64(contentLength);
    const auto pump = [socket, file, remaining]() {
        static const qint64 kChunkSize = 64 * 1024;
        static const qint64 kHighWater = 2 * 1024 * 1024;

        if (socket->state() != QAbstractSocket::ConnectedState) return;

        while (*remaining > 0 && socket->bytesToWrite() < kHighWater && !file->atEnd()) {
            const qint64 toRead = qMin(kChunkSize, *remaining);
            const QByteArray chunk = file->read(toRead);
            if (chunk.isEmpty()) break;
            *remaining -= chunk.size();
            if (socket->write(chunk) < 0) {
                socket->disconnectFromHost();
                return;
            }
        }

        if ((*remaining <= 0 || file->atEnd()) && socket->bytesToWrite() == 0) {
            file->close();
            socket->disconnectFromHost();
        }
    };

    connect(socket, &QTcpSocket::bytesWritten, socket, [pump](qint64) { pump(); });
    connect(socket, &QTcpSocket::disconnected, file, [file, remaining]() {
        delete remaining;
        file->deleteLater();
    });
    pump();
}
void ChatServer::handleRegister(ClientSession *session, const QJsonObject &data) {
    QString username = data["username"].toString();       // uniqueId
    QString displayName = data["displayName"].toString(); // 昵称
    QString password = data["password"].toString();
    QString passwordError;

    QJsonObject rspData;

    // 验证唯一ID格式：6-12位，仅允许字母、数字、下划线
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(username).hasMatch()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("用户ID必须为6-20位，只能包含字母、数字和下划线");
    } else if (displayName.trimmed().isEmpty()) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("请输入昵称");
    } else if (displayName.trimmed().size() > InputValidator::MAX_DISPLAY_NAME_CHARS) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("昵称过长");
    } else if (!InputValidator::validatePassword(password, &passwordError)) {
        rspData["success"] = false;
        rspData["error"] = passwordError;
    } else {
        if (!allowAuthenticationAttempt(session, username, QStringLiteral("register"),
                                        Protocol::MsgType::REGISTER_RSP)) {
            return;
        }
        int userId = m_db->registerUser(username, displayName.trimmed(), password);
        if (userId > 0) {
            m_authAbuseGuard.recordSuccess(username);
            rspData["success"]  = true;
            rspData["userId"]   = userId;
            rspData["username"] = username;
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

    const QJsonObject data = msg["data"].toObject();
    const int roomId = data["roomId"].toInt();
    const QString content = data["content"].toString();
    const QString contentType = data["contentType"].toString();
    const QString clientMessageId = data["clientMessageId"].toString().isEmpty()
                                        ? msg["id"].toString()
                                        : data["clientMessageId"].toString();

#ifdef CHATROOM_ENABLE_BENCHMARK_METRICS
    const bool benchmarkMetrics = qEnvironmentVariableIntValue("CHATROOM_BENCHMARK_METRICS") == 1;
    QElapsedTimer persistenceTimer;
    if (benchmarkMetrics)
        persistenceTimer.start();
#endif
    RoomMessageService::Command command;
    command.roomId = roomId;
    command.senderId = session->userId();
    command.clientMessageId = clientMessageId;
    command.content = content;
    command.contentType = contentType;
    const RoomMessageService::Result result = m_roomMessageService.submit(command);
    recordRoomMessageOutcome(result.status, session->userId(), roomId);
#ifdef CHATROOM_ENABLE_BENCHMARK_METRICS
    const qint64 sqliteSaveNanoseconds = benchmarkMetrics ? persistenceTimer.nsecsElapsed() : 0;
#endif

    const bool accepted = result.status == RoomMessageService::Status::Accepted;
    const bool duplicate = result.status == RoomMessageService::Status::Duplicate;
    QJsonObject responseData;
    responseData["success"] = accepted || duplicate;
    responseData["roomId"] = roomId;
    if (!clientMessageId.isEmpty() && clientMessageId.toUtf8().size() <= 128)
        responseData["clientMessageId"] = clientMessageId;
    if (accepted || duplicate) {
        responseData["id"] = result.messageId;
        responseData["sequence"] = static_cast<double>(result.sequence);
        responseData["timestamp"] = static_cast<double>(result.createdAtMs);
        responseData["duplicate"] = duplicate;
    } else {
        responseData["errorCode"] = result.errorCode;
        responseData["error"] = result.error;
        qWarning().noquote()
            << QStringLiteral("[Messaging] room-send rejected userId=%1 roomId=%2 code=%3")
                   .arg(session->userId())
                   .arg(roomId)
                   .arg(result.errorCode);
    }
    session->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::CHAT_SEND_RSP, responseData));

    if (!accepted) return;

    // 补全消息信息
    QJsonObject fullData;
    fullData["roomId"]     = roomId;
    fullData["content"]    = content;
    fullData["contentType"] = contentType;
    fullData["clientMessageId"] = clientMessageId;
    fullData["id"]         = result.messageId;
    fullData["sequence"]   = static_cast<double>(result.sequence);
    fullData["sender"]     = session->username();      // uniqueId
    fullData["senderName"] = session->displayName();   // 昵称
    QJsonObject fullMsg = Protocol::makeMessage(Protocol::MsgType::CHAT_MSG, fullData);
    fullMsg["timestamp"] = static_cast<double>(result.createdAtMs);

    broadcastToRoom(roomId, fullMsg);

#ifdef CHATROOM_ENABLE_BENCHMARK_METRICS
    if (benchmarkMetrics) {
        qInfo().noquote() << QStringLiteral("[M0_METRIC] sqlite_save_us=%1")
                                 .arg(sqliteSaveNanoseconds / 1000.0, 0, 'f', 3);
    }
#endif
}

// ==================== 房间管理 ====================

void ChatServer::handleCreateRoom(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString roomName = data["roomName"].toString();
    const QString password = data["password"].toString();
    QString passwordError;
    if (!password.isEmpty() &&
        !InputValidator::validatePassword(password, &passwordError)) {
        QJsonObject response{{"success", false}, {"error", passwordError}};
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::CREATE_ROOM_RSP, response));
        return;
    }
    int roomId = m_db->createRoom(roomName, session->userId(), password);

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

        if (!alreadyMember) {
            QJsonObject settings = m_db->getRoomSettings(roomId);
            int maxMembers = settings["maxMembers"].toInt(50);
            int currentMembers = m_db->getRoomMemberCount(roomId);
            if (maxMembers > 0 && currentMembers >= maxMembers) {
                rspData["success"] = false;
                rspData["roomId"]  = roomId;
                rspData["error"]   = QStringLiteral("聊天室人数已达上限");
                session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));
                return;
            }
        }

        // 首次加入时检查密码
        if (!alreadyMember && m_db->roomHasPassword(roomId)) {
            QString providedPwd = data["password"].toString();
            if (providedPwd.isEmpty()) {
                rspData["success"] = false;
                rspData["roomId"]  = roomId;
                rspData["needPassword"] = true;
                rspData["error"]   = QStringLiteral("该聊天室需要密码才能加入");
                session->sendMessage(Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_RSP, rspData));
                return;
            }
            if (!allowAuthenticationAttempt(
                    session, QStringLiteral("room:%1").arg(roomId),
                    QStringLiteral("join-room-password"),
                    Protocol::MsgType::JOIN_ROOM_RSP)) {
                return;
            }
            if (!m_db->verifyRoomPassword(roomId, providedPwd)) {
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

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-leave"))) {
        QJsonObject rspData;
        rspData["roomId"] = roomId;
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("您不在该聊天室中");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::LEAVE_ROOM_RSP, rspData));
        return;
    }

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
        const QStringList autoCosUrls = m_db->getCosUrlsForRoom(roomId);
        m_db->deleteRoom(roomId);
        m_roomMgr->removeRoom(roomId);
        deleteCosFiles(autoCosUrls);
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
    if (!session->isAuthenticated()) return;

    // 只返回用户已加入的房间（带未读计数）
    QJsonArray roomArr = m_db->getUserJoinedRooms(session->userId());
    for (int i = 0; i < roomArr.size(); ++i) {
        QJsonObject room = roomArr[i].toObject();
        int roomId = room["roomId"].toInt();
        room["unread"] = m_db->getUnreadRoomCount(roomId, session->userId());
        roomArr[i] = room;
    }
    QJsonObject rspData;
    rspData["rooms"] = roomArr;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_LIST_RSP, rspData));
}

void ChatServer::handleUserList(ClientSession *session, const QJsonObject &data) {
    int roomId = data["roomId"].toInt();

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-member-list"))) {
        QJsonObject rspData;
        rspData["roomId"] = roomId;
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权访问该聊天室");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_LIST_RSP, rspData));
        return;
    }

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
    rspData["success"] = true;
    rspData["users"]  = userArr;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_LIST_RSP, rspData));
}

void ChatServer::handleHistory(ClientSession *session, const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    int count  = InputValidator::boundedHistoryCount(data["count"].toInt(50));
    const qint64 before = static_cast<qint64>(data["before"].toDouble(0));
    const bool sequenceMode = data.contains("afterSequence");
    const qint64 afterSequence = static_cast<qint64>(data["afterSequence"].toDouble(0));

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-history-read"))) {
        QJsonObject rspData;
        rspData["roomId"] = roomId;
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权访问该聊天室");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::HISTORY_RSP, rspData));
        return;
    }

    if (sequenceMode && afterSequence < 0) {
        QJsonObject rspData;
        rspData["roomId"] = roomId;
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("INVALID_SEQUENCE_CURSOR");
        rspData["error"] = QStringLiteral("afterSequence 不能为负数");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::HISTORY_RSP, rspData));
        return;
    }

    RoomSyncPage syncPage;
    QJsonArray messages;
    if (sequenceMode) {
        syncPage = m_db->getRoomSyncPage(roomId, count, afterSequence);
        messages = syncPage.messages;
    } else {
        messages = m_db->getMessageHistory(roomId, count, before);
    }

    QJsonObject rspData;
    rspData["roomId"]   = roomId;
    rspData["success"]  = true;
    rspData["messages"] = messages;
    if (sequenceMode) {
        rspData["events"] = syncPage.events;
        const qint64 lastSequence = m_db->getRoomLastMessageSequence(roomId);
        qint64 nextSequence = lastSequence;
        if (syncPage.itemCount == count)
            nextSequence = syncPage.nextSequence;
        rspData["mode"] = QStringLiteral("sequence");
        rspData["afterSequence"] = static_cast<double>(afterSequence);
        rspData["nextSequence"] = static_cast<double>(nextSequence);
        rspData["lastSequence"] = static_cast<double>(lastSequence);
        rspData["hasMore"] = nextSequence < lastSequence;
    }
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

bool ChatServer::tryReserveRoomFileQuota(int roomId, qint64 fileSize, QString *error) {
    if (fileSize <= 0) {
        if (error) *error = QStringLiteral("文件大小无效");
        return false;
    }

    QJsonObject settings = m_db->getRoomSettings(roomId);
    qint64 maxSize = static_cast<qint64>(settings["maxFileSize"].toDouble());
    qint64 maxTotal = static_cast<qint64>(settings["totalFileSpace"].toDouble());
    int maxCount = settings["maxFileCount"].toInt(1500);

    if (maxSize > 0 && fileSize > maxSize) {
        if (error) *error = QString("文件大小超过房间限制(%1MB)").arg(maxSize / 1024 / 1024);
        return false;
    }

    qint64 usedTotal = m_db->getRoomUsedFileSpace(roomId);
    int fileCount = m_db->getRoomFileCount(roomId);
    qint64 reservedBytes = m_roomReservedBytes.value(roomId, 0);
    int reservedCount = m_roomReservedCount.value(roomId, 0);

    if (maxTotal > 0 && usedTotal + reservedBytes + fileSize > maxTotal) {
        if (error) *error = QStringLiteral("聊天室总文件空间已达上限");
        return false;
    }
    if (maxCount > 0 && fileCount + reservedCount + 1 > maxCount) {
        if (error) *error = QStringLiteral("聊天室文件数量已达上限");
        return false;
    }

    m_roomReservedBytes[roomId] = reservedBytes + fileSize;
    m_roomReservedCount[roomId] = reservedCount + 1;
    return true;
}

void ChatServer::releaseRoomFileQuota(int roomId, qint64 fileSize) {
    if (roomId <= 0 || fileSize <= 0) return;

    qint64 reservedBytes = m_roomReservedBytes.value(roomId, 0) - fileSize;
    int reservedCount = m_roomReservedCount.value(roomId, 0) - 1;

    if (reservedBytes > 0) m_roomReservedBytes[roomId] = reservedBytes;
    else m_roomReservedBytes.remove(roomId);

    if (reservedCount > 0) m_roomReservedCount[roomId] = reservedCount;
    else m_roomReservedCount.remove(roomId);
}

QList<int> ChatServer::buildCleanupPlan(int roomId, qint64 newMaxFileSize, qint64 newTotalFileSpace,
                                        int newMaxFileCount, QJsonObject *planSummary) {
    QJsonArray files = m_db->getRoomActiveFilesOrdered(roomId);
    QList<int> cleanupIds;
    QSet<int> selected;

    qint64 currentUsed = 0;
    for (const QJsonValue &v : files)
        currentUsed += static_cast<qint64>(v.toObject()["fileSize"].toDouble());
    int currentCount = files.size();

    // 1) 新单文件上限：清理所有超限文件
    for (const QJsonValue &v : files) {
        QJsonObject f = v.toObject();
        int fileId = f["fileId"].toInt();
        qint64 size = static_cast<qint64>(f["fileSize"].toDouble());
        if (newMaxFileSize > 0 && size > newMaxFileSize && !selected.contains(fileId)) {
            selected.insert(fileId);
            cleanupIds.append(fileId);
        }
    }

    // 2) 新总空间/数量上限：按最早文件继续清理到满足条件
    qint64 afterUsed = currentUsed;
    int afterCount = currentCount;
    for (int fileId : cleanupIds) {
        for (const QJsonValue &v : files) {
            QJsonObject f = v.toObject();
            if (f["fileId"].toInt() == fileId) {
                afterUsed -= static_cast<qint64>(f["fileSize"].toDouble());
                afterCount -= 1;
                break;
            }
        }
    }

    for (const QJsonValue &v : files) {
        bool totalExceeded = newTotalFileSpace > 0 && afterUsed > newTotalFileSpace;
        bool countExceeded = newMaxFileCount > 0 && afterCount > newMaxFileCount;
        if (!totalExceeded && !countExceeded) break;

        QJsonObject f = v.toObject();
        int fileId = f["fileId"].toInt();
        if (selected.contains(fileId)) continue;

        selected.insert(fileId);
        cleanupIds.append(fileId);
        afterUsed -= static_cast<qint64>(f["fileSize"].toDouble());
        afterCount -= 1;
    }

    if (planSummary) {
        planSummary->insert("currentUsedSpace", static_cast<double>(currentUsed));
        planSummary->insert("currentFileCount", currentCount);
        planSummary->insert("afterUsedSpace", static_cast<double>(qMax<qint64>(0, afterUsed)));
        planSummary->insert("afterFileCount", qMax(0, afterCount));
        planSummary->insert("clearFileCount", cleanupIds.size());
        QJsonArray ids;
        for (int id : cleanupIds) ids.append(id);
        planSummary->insert("clearFileIds", ids);
    }

    return cleanupIds;
}

bool ChatServer::applyFileCleanupPlan(int roomId, const QList<int> &fileIds, const QString &reason, QJsonArray *clearedIdsOut) {
    if (fileIds.isEmpty()) return true;

    QJsonArray files = m_db->getRoomActiveFilesOrdered(roomId);
    QMap<int, QString> pathById;
    for (const QJsonValue &v : files) {
        QJsonObject f = v.toObject();
        pathById[f["fileId"].toInt()] = f["filePath"].toString();
    }

    // 在标记清除前查询 COS URL
    const QStringList cosUrls = m_db->getCosUrlsForFileIds(fileIds);

    if (!m_db->markRoomFilesCleared(roomId, fileIds, reason)) {
        return false;
    }

    for (int fileId : fileIds) {
        QString filePath = pathById.value(fileId);
        if (!filePath.isEmpty()) {
            QFile::remove(filePath);
        }
        if (clearedIdsOut)
            clearedIdsOut->append(fileId);
    }

    deleteCosFiles(cosUrls);
    return true;
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

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-file-send"))) {
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = QStringLiteral("无权向该聊天室发送文件");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }

    QByteArray rawData;
    QString validationError;
    QString validatedFileName;
    if (!InputValidator::validateFileName(fileName, &validatedFileName, &validationError)
        || !InputValidator::decodeInlineFile(fileData, fileSize, Protocol::MAX_SMALL_FILE,
                                             &rawData, &validationError)) {
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }
    fileName = validatedFileName;

    QString quotaError;
    if (!tryReserveRoomFileQuota(roomId, fileSize, &quotaError)) {
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = quotaError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }

    // 保存文件到服务器磁盘（多级目录：{roomId}/类型/年月/）
    QString targetDir = serverFileDir(roomId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    QFile file(filePath);
    if (file.open(QIODevice::WriteOnly)) {
        if (file.write(rawData) != rawData.size()) {
            file.close();
            QFile::remove(filePath);
            releaseRoomFileQuota(roomId, fileSize);
            QJsonObject rsp;
            rsp["roomId"] = roomId;
            rsp["success"] = false;
            rsp["error"] = QStringLiteral("服务器写入文件失败");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
            return;
        }
        file.close();
    } else {
        releaseRoomFileQuota(roomId, fileSize);
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = QStringLiteral("服务器无法创建文件");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }

    // 保存文件信息到数据库
    int fileId = m_db->saveFile(roomId, session->userId(), fileName, filePath, fileSize);
    if (fileId <= 0) {
        QFile::remove(filePath);
        releaseRoomFileQuota(roomId, fileSize);
        QJsonObject rsp;
        rsp["roomId"] = roomId;
        rsp["success"] = false;
        rsp["error"] = QStringLiteral("文件保存失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, rsp));
        return;
    }

    // 根据文件后缀确定 contentType
    QString contentType = QStringLiteral("file");
    QString typeDir = fileTypeSubDir(fileName);
    if (typeDir == QLatin1String("Image"))
        contentType = QStringLiteral("image");
    else if (typeDir == QLatin1String("Video"))
        contentType = QStringLiteral("video");

    // 自动生成缩略图
    QString thumbnail;
#ifndef CHATROOM_DISABLE_IMAGE_THUMBNAILS
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
#endif
    // QImage 失败或非图片类型时，使用客户端提供的缩略图
    if (thumbnail.isEmpty() && data.contains("thumbnail")) {
        thumbnail = data["thumbnail"].toString();
    }

    // 保存消息记录（含缩略图）
    qint64 sequence = 0;
    qint64 timestamp = 0;
    int msgId = m_db->saveMessage(roomId, session->userId(), fileName, contentType,
                                  fileName, fileSize, fileId, thumbnail,
                                  &sequence, &timestamp);
    if (msgId <= 0) {
        m_db->deleteStoredFileRecord(fileId);
        QFile::remove(filePath);
        releaseRoomFileQuota(roomId, fileSize);
        return;
    }

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
    notifyData["sequence"]    = static_cast<double>(sequence);
    notifyData["timestamp"]   = static_cast<double>(timestamp);

    if (!thumbnail.isEmpty())
        notifyData["thumbnail"] = thumbnail;
    notifyData["fileCleared"] = false;

    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));
    releaseRoomFileQuota(roomId, fileSize);
}

void ChatServer::handleFileDownload(ClientSession *session, const QJsonObject &data) {
    int fileId = data["fileId"].toInt();

    // 负数 fileId 表示好友文件
    bool isFriendFile = (fileId < 0);
    int dbFileId = isFriendFile ? -fileId : fileId;

    QJsonObject rspData;
    rspData["fileId"] = fileId;
    if (!session->isAuthenticated()
        || !m_db->canUserAccessFile(dbFileId, isFriendFile, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权访问该文件");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_RSP, rspData));
        qWarning().noquote() << QStringLiteral("[Authz] denied operation=file-download userId=%1 fileId=%2 friend=%3")
                                    .arg(session->isAuthenticated() ? session->userId() : 0)
                                    .arg(dbFileId)
                                    .arg(isFriendFile);
        return;
    }
    QString dbFileName = m_db->getFileName(dbFileId, isFriendFile);

    // 如果 COS 已启用且文件已上传，返回 COS URL 让客户端直接下载
    if (m_cos->isEnabled()) {
        QString cosUrl = m_db->getCosUrl(dbFileId, isFriendFile);
        if (!cosUrl.isEmpty()) {
            rspData["success"]  = true;
            rspData["fileId"]   = fileId;
            rspData["fileName"] = dbFileName.isEmpty() ? data["fileName"].toString() : dbFileName;
            rspData["cosUrl"]   = m_cos->presignedUrl(cosUrl);
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_RSP, rspData));
            return;
        }
    }

    QString filePath = m_db->getFilePath(dbFileId, isFriendFile);
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

void ChatServer::handleFileForward(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QJsonObject response;
    const double sourceFileValue = data["sourceFileId"].toDouble();
    if (!std::isfinite(sourceFileValue) || sourceFileValue == 0 ||
        std::floor(sourceFileValue) != sourceFileValue ||
        std::abs(sourceFileValue) > std::numeric_limits<int>::max()) {
        response["success"] = false;
        response["forwardedCount"] = 0;
        response["failedCount"] = 0;
        response["errorCode"] = QStringLiteral("INVALID_SOURCE_FILE_ID");
        response["error"] = QStringLiteral("源文件标识无效");
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_RSP, response));
        return;
    }
    const qint64 signedFileId = static_cast<qint64>(sourceFileValue);
    const bool isFriendFile = signedFileId < 0;
    const qint64 absoluteFileId = isFriendFile ? -signedFileId : signedFileId;
    if (absoluteFileId <= 0 || absoluteFileId > std::numeric_limits<int>::max() ||
        !m_db->canUserAccessFile(static_cast<int>(absoluteFileId), isFriendFile,
                                 session->userId())) {
        qWarning().noquote()
            << QStringLiteral("[Authz] denied operation=file-forward-source userId=%1 fileId=%2 friend=%3")
                   .arg(session->userId()).arg(absoluteFileId).arg(isFriendFile);
        response["success"] = false;
        response["forwardedCount"] = 0;
        response["failedCount"] = 0;
        response["errorCode"] = QStringLiteral("SOURCE_FILE_ACCESS_DENIED");
        response["error"] = QStringLiteral("无权访问源文件或文件已过期");
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_RSP, response));
        return;
    }

    const QString sourcePath = m_db->getFilePath(static_cast<int>(absoluteFileId),
                                                  isFriendFile);
    const QString fileName = m_db->getFileName(static_cast<int>(absoluteFileId),
                                               isFriendFile);
    const QFileInfo sourceInfo(sourcePath);
    const qint64 fileSize = sourceInfo.size();
    QString validatedFileName;
    QString fileNameError;
    const bool validFileName = InputValidator::validateFileName(
        fileName, &validatedFileName, &fileNameError);
    if (sourcePath.isEmpty() || !validFileName || validatedFileName != fileName ||
        !sourceInfo.isFile() ||
        fileSize <= 0 || fileSize > Protocol::MAX_SMALL_FILE) {
        response["success"] = false;
        response["forwardedCount"] = 0;
        response["failedCount"] = 0;
        response["errorCode"] = fileSize > Protocol::MAX_SMALL_FILE
            ? QStringLiteral("SOURCE_FILE_TOO_LARGE")
            : QStringLiteral("SOURCE_FILE_UNAVAILABLE");
        response["error"] = fileSize > Protocol::MAX_SMALL_FILE
            ? QStringLiteral("当前服务端转发上限为 8MB")
            : QStringLiteral("源文件不可用");
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_RSP, response));
        return;
    }

    QSet<int> roomIds;
    for (const QJsonValue &value : data["roomIds"].toArray()) {
        const int roomId = value.toInt();
        if (roomId > 0) roomIds.insert(roomId);
    }
    QSet<QString> friendUsernames;
    for (const QJsonValue &value : data["friendUsernames"].toArray()) {
        const QString username = value.toString().trimmed();
        if (!username.isEmpty() && username.size() <= InputValidator::MAX_USERNAME_CHARS)
            friendUsernames.insert(username);
    }
    const int targetCount = roomIds.size() + friendUsernames.size();
    if (targetCount <= 0 || targetCount > Protocol::MAX_FILE_FORWARD_TARGETS) {
        qWarning().noquote()
            << QStringLiteral("[Input] rejected category=file-forward-targets userId=%1 count=%2")
                   .arg(session->userId()).arg(targetCount);
        response["success"] = false;
        response["forwardedCount"] = 0;
        response["failedCount"] = 0;
        response["errorCode"] = QStringLiteral("INVALID_FORWARD_TARGETS");
        response["error"] = QStringLiteral("转发目标数量必须在 1 到 %1 之间")
                                .arg(Protocol::MAX_FILE_FORWARD_TARGETS);
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_RSP, response));
        return;
    }

    QJsonArray results;
    int forwardedCount = 0;
    for (int roomId : roomIds) {
        QJsonObject result = forwardFileToRoom(session, roomId, sourcePath,
                                               fileName, fileSize);
        if (result["success"].toBool()) ++forwardedCount;
        results.append(result);
    }
    for (const QString &friendUsername : friendUsernames) {
        QJsonObject result = forwardFileToFriend(session, friendUsername, sourcePath,
                                                 fileName, fileSize);
        if (result["success"].toBool()) ++forwardedCount;
        results.append(result);
    }

    response["success"] = forwardedCount > 0;
    response["forwardedCount"] = forwardedCount;
    response["failedCount"] = targetCount - forwardedCount;
    response["results"] = results;
    if (forwardedCount == 0)
        response["error"] = QStringLiteral("所有转发目标均失败");
    qInfo().noquote()
        << QStringLiteral("[AttachmentForward] userId=%1 targets=%2 accepted=%3 rejected=%4")
               .arg(session->userId()).arg(targetCount).arg(forwardedCount)
               .arg(targetCount - forwardedCount);
    session->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_RSP, response));
}

QJsonObject ChatServer::forwardFileToRoom(ClientSession *session, int roomId,
                                          const QString &sourcePath,
                                          const QString &fileName,
                                          qint64 fileSize) {
    QJsonObject result;
    result["targetType"] = QStringLiteral("room");
    result["roomId"] = roomId;
    if (!m_db->isUserInRoom(roomId, session->userId())) {
        qWarning().noquote()
            << QStringLiteral("[Authz] denied operation=file-forward-room userId=%1 roomId=%2")
                   .arg(session->userId()).arg(roomId);
        result["success"] = false;
        result["errorCode"] = QStringLiteral("ROOM_ACCESS_DENIED");
        return result;
    }

    QString quotaError;
    if (!tryReserveRoomFileQuota(roomId, fileSize, &quotaError)) {
        result["success"] = false;
        result["errorCode"] = QStringLiteral("ROOM_FILE_QUOTA_EXCEEDED");
        result["error"] = quotaError;
        return result;
    }

    const QString targetPath = serverFileDir(roomId, fileName) + "/" +
        QUuid::createUuid().toString(QUuid::WithoutBraces) + "_" + fileName;
    if (!QFile::copy(sourcePath, targetPath)) {
        releaseRoomFileQuota(roomId, fileSize);
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FILE_COPY_FAILED");
        return result;
    }

    const int fileId = m_db->saveFile(roomId, session->userId(), fileName,
                                      targetPath, fileSize);
    const QString typeDir = fileTypeSubDir(fileName);
    const QString contentType = typeDir == QLatin1String("Image")
        ? QStringLiteral("image")
        : (typeDir == QLatin1String("Video")
               ? QStringLiteral("video") : QStringLiteral("file"));
    const QString thumbnail = buildForwardThumbnail(targetPath, fileName, fileSize);
    qint64 sequence = 0;
    qint64 timestamp = 0;
    const int messageId = fileId > 0
        ? m_db->saveMessage(roomId, session->userId(), fileName, contentType,
                            fileName, fileSize, fileId, thumbnail,
                            &sequence, &timestamp)
        : -1;
    if (fileId <= 0 || messageId <= 0) {
        if (fileId > 0) m_db->deleteStoredFileRecord(fileId);
        QFile::remove(targetPath);
        releaseRoomFileQuota(roomId, fileSize);
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FORWARD_PERSIST_FAILED");
        return result;
    }

    QJsonObject notify;
    notify["id"] = messageId;
    notify["roomId"] = roomId;
    notify["sender"] = session->username();
    notify["senderName"] = session->displayName();
    notify["fileName"] = fileName;
    notify["fileSize"] = static_cast<double>(fileSize);
    notify["fileId"] = fileId;
    notify["contentType"] = contentType;
    notify["content"] = fileName;
    notify["fileCleared"] = false;
    notify["sequence"] = static_cast<double>(sequence);
    notify["timestamp"] = static_cast<double>(timestamp);
    if (!thumbnail.isEmpty()) notify["thumbnail"] = thumbnail;
    broadcastToRoom(roomId,
                    Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notify));
    releaseRoomFileQuota(roomId, fileSize);
    result["success"] = true;
    result["fileId"] = fileId;
    result["messageId"] = messageId;
    return result;
}

QJsonObject ChatServer::forwardFileToFriend(ClientSession *session,
                                            const QString &friendUsername,
                                            const QString &sourcePath,
                                            const QString &fileName,
                                            qint64 fileSize) {
    QJsonObject result;
    result["targetType"] = QStringLiteral("friend");
    result["friendUsername"] = friendUsername;
    const int friendId = m_db->getUserIdByName(friendUsername);
    const int friendshipId = friendId > 0
        ? m_db->getFriendshipId(session->userId(), friendId) : -1;
    if (friendshipId <= 0) {
        qWarning().noquote()
            << QStringLiteral("[Authz] denied operation=file-forward-friend userId=%1 targetUserId=%2")
                   .arg(session->userId()).arg(friendId);
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FRIENDSHIP_ACCESS_DENIED");
        return result;
    }
    if (fileSize > Protocol::MAX_FRIEND_FILE) {
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FRIEND_FILE_TOO_LARGE");
        return result;
    }

    const QString targetPath = friendFileDir(friendshipId, fileName) + "/" +
        QUuid::createUuid().toString(QUuid::WithoutBraces) + "_" + fileName;
    if (!QFile::copy(sourcePath, targetPath)) {
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FILE_COPY_FAILED");
        return result;
    }

    const int fileId = m_db->saveFriendFile(friendshipId, session->userId(),
                                            fileName, targetPath, fileSize);
    const QString typeDir = fileTypeSubDir(fileName);
    const QString contentType = typeDir == QLatin1String("Image")
        ? QStringLiteral("image")
        : (typeDir == QLatin1String("Video")
               ? QStringLiteral("video") : QStringLiteral("file"));
    const QString thumbnail = buildForwardThumbnail(targetPath, fileName, fileSize);
    qint64 sequence = 0;
    qint64 timestamp = 0;
    const int messageId = fileId > 0
        ? m_db->saveFriendMessage(friendshipId, session->userId(), fileName,
                                  contentType, fileName, fileSize, fileId, thumbnail,
                                  &sequence, &timestamp)
        : -1;
    if (fileId <= 0 || messageId <= 0) {
        if (fileId > 0) m_db->deleteStoredFileRecord(fileId, true);
        QFile::remove(targetPath);
        result["success"] = false;
        result["errorCode"] = QStringLiteral("FORWARD_PERSIST_FAILED");
        return result;
    }

    QJsonObject notify;
    notify["id"] = messageId;
    notify["friendshipId"] = friendshipId;
    notify["sender"] = session->username();
    notify["senderName"] = session->displayName();
    notify["friendUsername"] = friendUsername;
    notify["content"] = fileName;
    notify["contentType"] = contentType;
    notify["fileName"] = fileName;
    notify["fileSize"] = static_cast<double>(fileSize);
    notify["fileId"] = -fileId;
    notify["sequence"] = static_cast<double>(sequence);
    notify["timestamp"] = static_cast<double>(timestamp);
    if (!thumbnail.isEmpty()) notify["thumbnail"] = thumbnail;
    const QJsonObject notification =
        Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, notify);
    session->sendMessage(notification);
    if (friendUsername != session->username())
        sendToUser(friendUsername, notification);
    result["success"] = true;
    result["fileId"] = -fileId;
    result["messageId"] = messageId;
    return result;
}

// ==================== 大文件分块传输 ====================

void ChatServer::handleFileUploadStart(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId       = data["roomId"].toInt();
    QString fileName = data["fileName"].toString();
    qint64 fileSize  = static_cast<qint64>(data["fileSize"].toDouble());
    const QString clientMessageId = data["clientMessageId"].toString();

    QJsonObject rspData;
    if (!validOptionalClientMessageId(clientMessageId)) {
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("INVALID_CLIENT_MESSAGE_ID");
        rspData["error"] = QStringLiteral("客户端消息 ID 过长");
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-file-upload-start"))) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权向该聊天室上传文件");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    QString validationError;
    QString validatedFileName;
    if (!InputValidator::validateFileName(fileName, &validatedFileName, &validationError)
        || fileSize <= 0) {
        rspData["success"] = false;
        rspData["error"] = validationError.isEmpty()
            ? QStringLiteral("文件大小无效") : validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }
    fileName = validatedFileName;

    if (fileSize > Protocol::MAX_LARGE_FILE) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件超过大小限制");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    QString quotaError;
    if (!tryReserveRoomFileQuota(roomId, fileSize, &quotaError)) {
        rspData["success"] = false;
        rspData["error"]   = quotaError;
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
        releaseRoomFileQuota(roomId, fileSize);
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
    state.clientMessageId = clientMessageId;
    state.fileName    = fileName;
    state.filePath = filePath;
    state.fileSize = fileSize;
    state.received = 0;
    state.roomQuotaReserved = true;
    state.file     = file;
    m_uploads[uploadId] = state;

    rspData["success"]  = true;
    rspData["uploadId"] = uploadId;
    if (!clientMessageId.isEmpty()) rspData["clientMessageId"] = clientMessageId;
    rspData["httpUploadPath"] = QStringLiteral("/api/upload/%1").arg(uploadId);
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START_RSP, rspData));

    qInfo() << "[Server] 大文件上传开始:" << fileName << fileSize << "bytes, uploadId:" << uploadId;
}

void ChatServer::handleFileUploadChunk(ClientSession *session, const QJsonObject &data) {
    QString uploadId = data["uploadId"].toString();

    QJsonObject rspData;
    rspData["uploadId"] = uploadId;

    if (!m_uploads.contains(uploadId)) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("无效的上传ID");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
        return;
    }

    if (!requireUploadOwnership(session, uploadId, &rspData)) {
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
        return;
    }

    UploadState &state = m_uploads[uploadId];
    QByteArray chunk;
    QString validationError;
    if (!InputValidator::decodeUploadChunk(data["chunkData"].toString(),
                                           state.fileSize - state.received,
                                           &chunk, &validationError)) {
        rspData["success"] = false;
        rspData["error"] = validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
        return;
    }

    if (!state.file || !state.file->isOpen() || state.file->write(chunk) != chunk.size()) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("服务器写入分片失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
        handleFileUploadCancel(session, data);
        return;
    }
    state.received += chunk.size();

    rspData["success"]  = true;
    rspData["received"] = static_cast<double>(state.received);
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK_RSP, rspData));
}

void ChatServer::handleFileUploadEnd(ClientSession *session, const QJsonObject &data) {
    const QString uploadId = data["uploadId"].toString();
    const QString requestedClientMessageId = data["clientMessageId"].toString();
    if (!validOptionalClientMessageId(requestedClientMessageId)) {
        MessageSaveResult rejected;
        sendUploadFinalizeResponse(session, uploadId, requestedClientMessageId,
                                   rejected, false,
                                   QStringLiteral("INVALID_CLIENT_MESSAGE_ID"),
                                   QStringLiteral("客户端消息 ID 过长"));
        return;
    }

    if (!m_uploads.contains(uploadId)) {
        if (!requestedClientMessageId.isEmpty() && session->isAuthenticated()) {
            const MessageSaveResult room =
                m_db->findRoomAttachmentByClientMessageId(
                    session->userId(), requestedClientMessageId);
            const MessageSaveResult friendResult =
                m_db->findFriendAttachmentByClientMessageId(
                    session->userId(), requestedClientMessageId);
            const bool roomFound = room.status != MessageSaveResult::Status::Failed;
            const bool friendFound = friendResult.status != MessageSaveResult::Status::Failed;
            if (roomFound != friendFound) {
                const MessageSaveResult &found = roomFound ? room : friendResult;
                if (found.status == MessageSaveResult::Status::Duplicate) {
                    sendUploadFinalizeResponse(session, uploadId,
                                               requestedClientMessageId, found,
                                               friendFound);
                } else {
                    sendUploadFinalizeResponse(
                        session, uploadId, requestedClientMessageId, found,
                        friendFound, QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT"),
                        QStringLiteral("客户端消息 ID 已用于其他命令"));
                }
                return;
            }
            if (roomFound && friendFound) {
                MessageSaveResult conflict;
                conflict.status = MessageSaveResult::Status::Conflict;
                sendUploadFinalizeResponse(
                    session, uploadId, requestedClientMessageId, conflict, false,
                    QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT"),
                    QStringLiteral("客户端消息 ID 同时匹配多个会话"));
                return;
            }
        }
        MessageSaveResult missing;
        sendUploadFinalizeResponse(session, uploadId, requestedClientMessageId,
                                   missing, false,
                                   QStringLiteral("UNKNOWN_UPLOAD_ID"),
                                   QStringLiteral("上传 ID 不存在或已过期"));
        return;
    }
    if (!requireUploadOwnership(session, uploadId)) {
        MessageSaveResult rejected;
        sendUploadFinalizeResponse(session, uploadId, requestedClientMessageId,
                                   rejected, false,
                                   QStringLiteral("UPLOAD_OWNER_MISMATCH"),
                                   QStringLiteral("无权完成该上传"));
        return;
    }

    const UploadState &pending = m_uploads[uploadId];
    const QString clientMessageId = pending.clientMessageId;
    if (!requestedClientMessageId.isEmpty() &&
        requestedClientMessageId != clientMessageId) {
        MessageSaveResult conflict;
        conflict.status = MessageSaveResult::Status::Conflict;
        sendUploadFinalizeResponse(
            session, uploadId, requestedClientMessageId, conflict,
            pending.roomId < 0, QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT"),
            QStringLiteral("完成请求与上传开始的客户端消息 ID 不一致"));
        handleFileUploadCancel(session, data);
        return;
    }
    const bool stillAuthorized = pending.roomId < 0
        ? m_db->isUserInFriendship(-pending.roomId, pending.userId)
        : m_db->isUserInRoom(pending.roomId, pending.userId);
    if (!stillAuthorized) {
        qWarning().noquote() << QStringLiteral("[Authz] denied operation=upload-finalize userId=%1")
                                    .arg(pending.userId);
        MessageSaveResult rejected;
        sendUploadFinalizeResponse(session, uploadId, clientMessageId, rejected,
                                   pending.roomId < 0,
                                   QStringLiteral("UPLOAD_AUTHORIZATION_REVOKED"),
                                   QStringLiteral("会话权限已变更，无法完成上传"));
        handleFileUploadCancel(session, data);
        return;
    }
    if (pending.received != pending.fileSize) {
        qWarning().noquote() << QStringLiteral("[Input] rejected category=upload-size userId=%1")
                                    .arg(pending.userId);
        MessageSaveResult rejected;
        sendUploadFinalizeResponse(session, uploadId, clientMessageId, rejected,
                                   pending.roomId < 0,
                                   QStringLiteral("UPLOAD_INCOMPLETE"),
                                   QStringLiteral("文件字节尚未全部上传"));
        handleFileUploadCancel(session, data);
        return;
    }

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
#ifndef CHATROOM_DISABLE_IMAGE_THUMBNAILS
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
#endif
    // QImage 失败或非图片类型时，使用客户端提供的缩略图
    if (thumbnail.isEmpty() && data.contains("thumbnail")) {
        thumbnail = data["thumbnail"].toString();
    }

    auto cleanupCandidate = [this, &state](int fileId, bool isFriendFile) {
        if (fileId > 0) m_db->deleteStoredFileRecord(fileId, isFriendFile);
        QFile::remove(state.filePath);
    };

    // 保存文件信息到数据库
    if (state.roomId < 0) {
        // 好友文件上传 (roomId = -friendshipId)
        int friendshipId = -state.roomId;
        int fileId = m_db->saveFriendFile(friendshipId, state.userId, state.fileName, state.filePath, state.fileSize);
        if (fileId <= 0) {
            QFile::remove(state.filePath);
            MessageSaveResult failed;
            sendUploadFinalizeResponse(session, uploadId, clientMessageId,
                                       failed, true);
            return;
        }
        MessageSaveResult saveResult;
        if (!clientMessageId.isEmpty()) {
            saveResult = m_db->saveFriendAttachmentIdempotent(
                friendshipId, state.userId, clientMessageId, state.fileName,
                contentType, state.fileSize, fileId, thumbnail);
        } else {
            saveResult.messageId = m_db->saveFriendMessage(
                friendshipId, state.userId, state.fileName, contentType,
                state.fileName, state.fileSize, fileId, thumbnail,
                &saveResult.sequence, &saveResult.createdAtMs);
            saveResult.fileId = fileId;
            saveResult.status = saveResult.messageId > 0
                ? MessageSaveResult::Status::Created
                : MessageSaveResult::Status::Failed;
        }
        if (saveResult.status != MessageSaveResult::Status::Created) {
            cleanupCandidate(fileId, true);
            sendUploadFinalizeResponse(
                session, uploadId, clientMessageId, saveResult, true,
                saveResult.status == MessageSaveResult::Status::Conflict
                    ? QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT") : QString(),
                saveResult.status == MessageSaveResult::Status::Conflict
                    ? QStringLiteral("客户端消息 ID 已用于其他命令") : QString());
            return;
        }

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
        notifyData["id"]           = saveResult.messageId;
        notifyData["friendshipId"] = friendshipId;
        notifyData["sender"]       = state.username;
        notifyData["senderName"]   = state.displayName;
        notifyData["friendUsername"] = friendUsername;
        notifyData["fileName"]     = state.fileName;
        notifyData["fileSize"]     = static_cast<double>(state.fileSize);
        notifyData["fileId"]       = -saveResult.fileId;  // 负数标识好友文件
        notifyData["contentType"]  = contentType;
        notifyData["content"]      = state.fileName;
        notifyData["sequence"]     = static_cast<double>(saveResult.sequence);
        notifyData["timestamp"]    = static_cast<double>(saveResult.createdAtMs);
        if (!clientMessageId.isEmpty())
            notifyData["clientMessageId"] = clientMessageId;
        if (!thumbnail.isEmpty())
            notifyData["thumbnail"] = thumbnail;

        QJsonObject notifyMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, notifyData);
        sendToUser(state.username, notifyMsg);
        if (!friendUsername.isEmpty() && friendUsername != state.username)
            sendToUser(friendUsername, notifyMsg);

        qInfo() << "[Server] 好友大文件上传完成:" << state.fileName << state.fileSize << "bytes";
        sendUploadFinalizeResponse(session, uploadId, clientMessageId,
                                   saveResult, true);

        // COS 异步上传（好友文件）
        if (m_cos->isEnabled()) {
            startCosUpload(state.filePath, state.fileName,
                           QStringLiteral("friends/%1/%2").arg(friendshipId).arg(typeDir),
                           saveResult.fileId, true, state.username, uploadId);
        }
    } else {
        // 房间文件上传
        int fileId = m_db->saveFile(state.roomId, state.userId, state.fileName, state.filePath, state.fileSize);
        if (fileId <= 0) {
            QFile::remove(state.filePath);
            if (state.roomQuotaReserved)
                releaseRoomFileQuota(state.roomId, state.fileSize);
            MessageSaveResult failed;
            sendUploadFinalizeResponse(session, uploadId, clientMessageId,
                                       failed, false);
            return;
        }
        MessageSaveResult saveResult;
        if (!clientMessageId.isEmpty()) {
            saveResult = m_db->saveRoomAttachmentIdempotent(
                state.roomId, state.userId, clientMessageId, state.fileName,
                contentType, state.fileSize, fileId, thumbnail);
        } else {
            saveResult.messageId = m_db->saveMessage(
                state.roomId, state.userId, state.fileName, contentType,
                state.fileName, state.fileSize, fileId, thumbnail,
                &saveResult.sequence, &saveResult.createdAtMs);
            saveResult.fileId = fileId;
            saveResult.status = saveResult.messageId > 0
                ? MessageSaveResult::Status::Created
                : MessageSaveResult::Status::Failed;
        }
        if (saveResult.status != MessageSaveResult::Status::Created) {
            cleanupCandidate(fileId, false);
            if (state.roomQuotaReserved)
                releaseRoomFileQuota(state.roomId, state.fileSize);
            sendUploadFinalizeResponse(
                session, uploadId, clientMessageId, saveResult, false,
                saveResult.status == MessageSaveResult::Status::Conflict
                    ? QStringLiteral("CLIENT_MESSAGE_ID_CONFLICT") : QString(),
                saveResult.status == MessageSaveResult::Status::Conflict
                    ? QStringLiteral("客户端消息 ID 已用于其他命令") : QString());
            return;
        }

        // 通知房间所有成员有新文件
        QJsonObject notifyData;
        notifyData["id"]          = saveResult.messageId;
        notifyData["roomId"]      = state.roomId;
        notifyData["sender"]      = state.username;
        notifyData["senderName"]  = state.displayName;
        notifyData["fileName"]    = state.fileName;
        notifyData["fileSize"]    = static_cast<double>(state.fileSize);
        notifyData["fileId"]      = saveResult.fileId;
        notifyData["contentType"] = contentType;
        notifyData["content"]     = state.fileName;
        notifyData["sequence"]    = static_cast<double>(saveResult.sequence);
        notifyData["timestamp"]   = static_cast<double>(saveResult.createdAtMs);
        if (!clientMessageId.isEmpty())
            notifyData["clientMessageId"] = clientMessageId;

        if (!thumbnail.isEmpty())
            notifyData["thumbnail"] = thumbnail;
        notifyData["fileCleared"] = false;

        broadcastToRoom(state.roomId, Protocol::makeMessage(Protocol::MsgType::FILE_NOTIFY, notifyData));

        if (state.roomQuotaReserved)
            releaseRoomFileQuota(state.roomId, state.fileSize);

        qInfo() << "[Server] 大文件上传完成:" << state.fileName << state.fileSize << "bytes";
        sendUploadFinalizeResponse(session, uploadId, clientMessageId,
                                   saveResult, false);

        // COS 异步上传（房间文件）
        if (m_cos->isEnabled()) {
            startCosUpload(state.filePath, state.fileName,
                           QStringLiteral("room/%1/%2").arg(state.roomId).arg(typeDir),
                           saveResult.fileId, false, state.username, uploadId);
        }
    }
}

void ChatServer::deleteCosFiles(const QStringList &cosUrls) {
    if (!m_cos->isEnabled()) return;
    for (const QString &url : cosUrls)
        m_cos->deleteCosFile(url);
}

void ChatServer::cleanupDeletedRoomFiles(const QJsonArray &fileIds) {
    QStringList cosUrls;
    QSet<int> seen;
    for (const QJsonValue &value : fileIds) {
        const int fileId = value.toInt();
        if (fileId <= 0 || seen.contains(fileId)) continue;
        seen.insert(fileId);
        const QString path = m_db->getFilePath(fileId, false);
        const QString cosUrl = m_db->getCosUrl(fileId, false);
        if (!path.isEmpty() && QFile::exists(path) && !QFile::remove(path)) {
            qWarning() << "[AdminDelete] 本地文件清理失败，保留记录供重试:" << fileId;
            continue;
        }
        if (!cosUrl.isEmpty()) cosUrls.append(cosUrl);
        m_db->deleteStoredFileRecord(fileId, false);
    }
    deleteCosFiles(cosUrls);
}

void ChatServer::startCosUpload(const QString &localPath, const QString &fileName,
                                 const QString &dirPrefix, int fileId, bool isFriendFile,
                                 const QString &uploaderUsername, const QString &uploadId)
{
    // 拼接 COS objectKey：prefix / dirPrefix / yyyy-MM / timestampFilename
    QString month = QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM"));
    QString objectKey = QStringLiteral("%1/%2/%3")
                            .arg(dirPrefix, month, QFileInfo(localPath).fileName());

    auto onProgress = [this, uploaderUsername, uploadId](qint64 sent, qint64 total) {
        QJsonObject pd;
        pd["uploadId"] = uploadId;
        pd["sent"]     = static_cast<double>(sent);
        pd["total"]    = static_cast<double>(total);
        sendToUser(uploaderUsername,
                   Protocol::makeMessage(Protocol::MsgType::FILE_COS_PROGRESS, pd));
    };

    auto onFinished = [this, fileId, isFriendFile, fileName, objectKey](bool ok, const QString &urlOrError) {
        if (ok) {
            m_db->setCosUrl(fileId, isFriendFile, urlOrError);
            qInfo() << "[COS] 上传成功:" << fileName << "->" << urlOrError;
        } else {
            qWarning() << "[COS] 上传失败:" << fileName << urlOrError;
        }
    };

    m_cos->uploadFile(localPath, objectKey, onProgress, onFinished);
    qInfo() << "[COS] 开始上传:" << fileName << "objectKey=" << objectKey;
}

void ChatServer::handleFileUploadCancel(ClientSession *session, const QJsonObject &data) {
    QString uploadId = data["uploadId"].toString();
    if (!m_uploads.contains(uploadId)) return;
    if (!requireUploadOwnership(session, uploadId)) return;

    UploadState state = m_uploads.take(uploadId);
    if (state.file) {
        state.file->close();
        delete state.file;
    }
    // 删除不完整的文件
    if (!state.filePath.isEmpty())
        QFile::remove(state.filePath);
    if (state.roomQuotaReserved)
        releaseRoomFileQuota(state.roomId, state.fileSize);
    qInfo() << "[Server] 上传已取消:" << state.fileName;
}

void ChatServer::abandonUpload(const QString &uploadId) {
    auto it = m_uploads.find(uploadId);
    if (it == m_uploads.end()) return;
    UploadState state = it.value();
    m_uploads.erase(it);
    if (state.file) {
        state.file->close();
        delete state.file;
    }
    if (!state.filePath.isEmpty()) QFile::remove(state.filePath);
    if (state.roomQuotaReserved)
        releaseRoomFileQuota(state.roomId, state.fileSize);
    qInfo().noquote() << QStringLiteral("[Upload] abandoned transport=http userId=%1")
                             .arg(state.userId);
}

void ChatServer::handleFileDownloadChunk(ClientSession *session, const QJsonObject &data) {
    int fileId    = data["fileId"].toInt();
    qint64 offset = static_cast<qint64>(data["offset"].toDouble());
    int chunkSize = data["chunkSize"].toInt();
    if (chunkSize <= 0) chunkSize = Protocol::FILE_CHUNK_SIZE;

    // 负数 fileId 表示好友文件
    bool isFriendFile = (fileId < 0);
    int dbFileId = isFriendFile ? -fileId : fileId;

    QJsonObject rspData;
    rspData["fileId"] = fileId;

    if (!session->isAuthenticated()
        || !m_db->canUserAccessFile(dbFileId, isFriendFile, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权访问该文件");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_RSP, rspData));
        qWarning().noquote() << QStringLiteral("[Authz] denied operation=file-download-chunk userId=%1 fileId=%2 friend=%3")
                                    .arg(session->isAuthenticated() ? session->userId() : 0)
                                    .arg(dbFileId)
                                    .arg(isFriendFile);
        return;
    }

    QString filePath = m_db->getFilePath(dbFileId, isFriendFile);
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

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-message-recall"))
        || !m_db->isMessageInRoom(messageId, roomId)) {
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("RECALL_ACCESS_DENIED");
        rspData["error"] = QStringLiteral("无权撤回该消息");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RECALL_RSP, rspData));
        return;
    }

    // 验证消息所有权和时间限制
    const RecallResult recall = m_db->recallMessage(
        messageId, session->userId(), Protocol::RECALL_TIME_LIMIT_SEC);
    const bool accepted = recall.status == RecallResult::Status::Applied ||
                          recall.status == RecallResult::Status::Duplicate;
    if (accepted) {
        // 如果是文件消息，清理服务器文件
        auto fileInfo = m_db->getFileInfoForMessage(messageId);
        if (fileInfo.first > 0) {
            const QString cosUrl = m_db->getCosUrl(fileInfo.first, false);
            if (!fileInfo.second.isEmpty()) {
                QFile::remove(fileInfo.second);
                qInfo() << "[Server] 撤回消息，已删除文件:" << fileInfo.second;
            }
            m_db->deleteFileRecords({fileInfo.first});
            if (!cosUrl.isEmpty())
                m_cos->deleteCosFile(cosUrl);
        }

        rspData["success"] = true;
        rspData["duplicate"] = recall.status == RecallResult::Status::Duplicate;
        if (recall.mutationSequence > 0)
            rspData["mutationSequence"] = static_cast<double>(recall.mutationSequence);
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::RECALL_RSP, rspData));

        if (recall.status == RecallResult::Status::Applied) {
            QJsonObject notifyData;
            notifyData["messageId"] = messageId;
            notifyData["roomId"]    = roomId;
            notifyData["username"]  = session->username();
            notifyData["mutationSequence"] =
                static_cast<double>(recall.mutationSequence);
            broadcastToRoom(roomId,
                Protocol::makeMessage(Protocol::MsgType::RECALL_NOTIFY, notifyData));
        }

        // 管理员撤回不再发额外系统消息，撤回通知已足够
    } else {
        rspData["success"] = false;
        rspData["errorCode"] = recall.status == RecallResult::Status::Failed
            ? QStringLiteral("RECALL_PERSISTENCE_FAILED")
            : QStringLiteral("RECALL_REJECTED");
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
    if (!m_db->isUserInRoom(roomId, targetUserId)) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("只能设置聊天室成员为管理员");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
        return;
    }

    if (!m_db->setRoomAdmin(roomId, targetUserId, setAdmin)) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("管理员权限更新失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_RSP, rspData));
        return;
    }

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

void ChatServer::handleDeleteMessages(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    const QJsonObject data = msg["data"].toObject();
    const int roomId = data["roomId"].toInt();
    const QString mode = data["mode"].toString();
    const QString clientOperationId = data["clientOperationId"].toString().isEmpty()
        ? msg["id"].toString() : data["clientOperationId"].toString();

    AdministrativeDeletionService::Command command;
    command.roomId = roomId;
    command.operatorUserId = session->userId();
    command.operatorName = session->displayName();
    command.clientOperationId = clientOperationId;
    command.mode = mode;
    command.cutoffMs = static_cast<qint64>(data["timestamp"].toDouble());
    for (const QJsonValue &value : data["messageIds"].toArray())
        command.messageIds.append(value.toInt());
    const AdministrativeDeletionService::Result result =
        m_administrativeDeletionService.execute(command);
    recordAdministrativeDeletionOutcome(result.status, session->userId(), roomId,
                                        result.sequence, clientOperationId);

    const bool accepted = result.status == AdministrativeDeletionService::Status::Accepted;
    const bool duplicate = result.status == AdministrativeDeletionService::Status::Duplicate;
    QJsonObject rspData;
    rspData["success"] = accepted || duplicate;
    rspData["roomId"] = roomId;
    rspData["mode"] = mode;
    rspData["clientOperationId"] = clientOperationId;
    if (!accepted && !duplicate) {
        rspData["errorCode"] = result.errorCode;
        rspData["error"] = result.error;
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_RSP, rspData));
        return;
    }

    rspData["duplicate"] = duplicate;
    rspData["deletedCount"] = result.deletedCount;
    rspData["messageIds"] = result.messageIds;
    rspData["deletedFileIds"] = result.deletedFileIds;
    rspData["timestamp"] = static_cast<double>(result.cutoffMs);
    rspData["sequence"] = static_cast<double>(result.sequence);
    rspData["syncSequence"] = static_cast<double>(result.sequence);
    rspData["eventTimestamp"] = static_cast<double>(result.createdAtMs);
    cleanupDeletedRoomFiles(result.deletedFileIds);
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_RSP, rspData));

    if (duplicate) return;

    QJsonObject notifyData = rspData;
    notifyData.remove("success");
    notifyData.remove("duplicate");
    notifyData["eventType"] = QStringLiteral("messagesDeleted");
    notifyData["operator"] = session->displayName();
    broadcastToRoom(roomId,
        Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_NOTIFY, notifyData),
        session);

    // 广播系统消息通知全体
    QString sysContent;
    if (mode == "all")
        sysContent = QString("管理员 %1 清空了所有聊天记录").arg(session->displayName());
    else if (mode == "selected")
        sysContent = QString("管理员 %1 删除了 %2 条消息").arg(session->displayName()).arg(result.deletedCount);
    else if (mode == "before")
        sysContent = QString("管理员 %1 删除了 %2 条旧消息").arg(session->displayName()).arg(result.deletedCount);
    else
        sysContent = QString("管理员 %1 删除了 %2 条近期消息").arg(session->displayName()).arg(result.deletedCount);
    broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
        {{"roomId", roomId}, {"content", sysContent}}));
}

void ChatServer::handleRoomFiles(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int roomId = data["roomId"].toInt();
    QJsonObject rspData;
    rspData["roomId"] = roomId;

    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("您没有管理员权限");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_RSP, rspData));
        return;
    }

    QJsonArray rawFiles = m_db->getRoomAllFiles(roomId);
    QJsonArray files;
    for (const QJsonValue &v : rawFiles) {
        QJsonObject src = v.toObject();
        if (src["cleared"].toBool(false)) {
            continue;
        }
        QJsonObject out;
        out["fileId"] = src["fileId"].toInt();
        out["fileName"] = src["fileName"].toString();
        out["fileSize"] = src["fileSize"].toDouble();
        out["cleared"] = src["cleared"].toBool(false);
        out["clearReason"] = src["clearReason"].toString();
        out["createdAt"] = src["createdAt"].toString();
        files.append(out);
    }

    QJsonObject settings = m_db->getRoomSettings(roomId);
    rspData["success"] = true;
    rspData["files"] = files;
    rspData["usedFileSpace"] = static_cast<double>(m_db->getRoomUsedFileSpace(roomId));
    rspData["maxFileSpace"] = static_cast<double>(settings["totalFileSpace"].toDouble());
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_RSP, rspData));
}

void ChatServer::handleRoomFilesDelete(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    const QJsonObject data = msg["data"].toObject();
    const int roomId = data["roomId"].toInt();
    const QString clientOperationId = data["clientOperationId"].toString().isEmpty()
        ? msg["id"].toString() : data["clientOperationId"].toString();
    QJsonObject rspData;
    rspData["roomId"] = roomId;
    rspData["clientOperationId"] = clientOperationId;

    AdministrativeDeletionService::Command command;
    command.roomId = roomId;
    command.operatorUserId = session->userId();
    command.operatorName = session->displayName();
    command.clientOperationId = clientOperationId;
    command.mode = QStringLiteral("selected");
    for (const QJsonValue &v : data["fileIds"].toArray()) {
        command.sourceFileIds.append(v.toInt());
    }
    const AdministrativeDeletionService::Result result =
        m_administrativeDeletionService.execute(command);
    recordAdministrativeDeletionOutcome(result.status, session->userId(), roomId,
                                        result.sequence, clientOperationId);

    const bool accepted = result.status == AdministrativeDeletionService::Status::Accepted;
    const bool duplicate = result.status == AdministrativeDeletionService::Status::Duplicate;
    rspData["success"] = accepted || duplicate;
    if (!accepted && !duplicate) {
        rspData["errorCode"] = result.errorCode;
        rspData["error"] = result.error;
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_DELETE_RSP, rspData));
        return;
    }

    rspData["duplicate"] = duplicate;
    rspData["deletedCount"] = result.deletedCount;
    rspData["messageIds"] = result.messageIds;
    rspData["deletedFileIds"] = result.deletedFileIds;
    rspData["sequence"] = static_cast<double>(result.sequence);
    rspData["syncSequence"] = static_cast<double>(result.sequence);
    rspData["eventTimestamp"] = static_cast<double>(result.createdAtMs);
    cleanupDeletedRoomFiles(result.deletedFileIds);

    QJsonObject settings = m_db->getRoomSettings(roomId);
    rspData["usedFileSpace"] = static_cast<double>(m_db->getRoomUsedFileSpace(roomId));
    rspData["maxFileSpace"] = static_cast<double>(settings["totalFileSpace"].toDouble());
    session->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_DELETE_RSP, rspData));

    if (duplicate) return;

    if (!result.messageIds.isEmpty()) {
        QJsonObject deleteNotify = rspData;
        deleteNotify.remove("success");
        deleteNotify.remove("duplicate");
        deleteNotify["mode"] = QStringLiteral("selected");
        deleteNotify["eventType"] = QStringLiteral("messagesDeleted");
        deleteNotify["operator"] = session->displayName();
        broadcastToRoom(roomId,
            Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_NOTIFY, deleteNotify),
            session);
    }

    if (!result.deletedFileIds.isEmpty()) {
        QJsonObject notifyData;
        notifyData["roomId"] = roomId;
        notifyData["deletedFileIds"] = result.deletedFileIds;
        notifyData["usedFileSpace"] = rspData["usedFileSpace"];
        notifyData["maxFileSpace"] = rspData["maxFileSpace"];
        notifyData["operator"] = session->displayName();
        broadcastToRoom(roomId,
            Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_NOTIFY, notifyData));

        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
            {{"roomId", roomId}, {"content", QString("管理员 %1 删除了 %2 条文件消息")
                                   .arg(session->displayName()).arg(result.deletedCount)}}));
    }
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

    QString passwordError;
    if (!password.isEmpty() &&
        !InputValidator::validatePassword(password, &passwordError)) {
        rspData["success"] = false;
        rspData["error"] = passwordError;
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_RSP, rspData));
        return;
    }
    if (!m_db->setRoomPassword(roomId, password)) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("服务器无法安全保存聊天室密码");
        session->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_RSP, rspData));
        return;
    }

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

    if (!m_db->isRoomAdmin(roomId, session->userId())) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("only admin can query password status");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::GET_ROOM_PASSWORD_RSP, rspData));
        return;
    }

    rspData["success"] = true;
    rspData["hasPassword"] = m_db->roomHasPassword(roomId);
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

    if (!requireRoomMembership(session, roomId, QStringLiteral("room-settings-read"))) {
        rspData["success"] = false;
        rspData["error"] = QStringLiteral("无权访问该聊天室");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
        return;
    }

    // 设置操作需要有效开发者秘钥
    if (data.contains("maxFileSize") || data.contains("totalFileSpace") ||
        data.contains("maxFileCount") || data.contains("maxMembers")) {
        if (!m_db->isRoomAdmin(roomId, session->userId())) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("只有管理员可以修改聊天室限制");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }
        QString keyError;
        if (!validateDeveloperKey(data["developerKey"].toString(), &keyError)) {
            rspData["success"] = false;
            rspData["error"] = keyError;
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }

        QJsonObject cur = m_db->getRoomSettings(roomId);
        qint64 maxFileSize = data.contains("maxFileSize")
            ? static_cast<qint64>(data["maxFileSize"].toDouble())
            : static_cast<qint64>(cur["maxFileSize"].toDouble());
        qint64 totalFileSpace = data.contains("totalFileSpace")
            ? static_cast<qint64>(data["totalFileSpace"].toDouble())
            : static_cast<qint64>(cur["totalFileSpace"].toDouble());
        int maxFileCount = data.contains("maxFileCount")
            ? data["maxFileCount"].toInt()
            : cur["maxFileCount"].toInt();
        int maxMembers = data.contains("maxMembers")
            ? data["maxMembers"].toInt()
            : cur["maxMembers"].toInt();

        if (maxFileSize <= 0 || totalFileSpace <= 0 || maxFileCount <= 0 || maxMembers <= 0) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("限制值必须大于0");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }
        if (totalFileSpace < maxFileSize) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("总文件空间不能小于单文件上限");
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }

        int currentMembers = m_db->getRoomMemberCount(roomId);
        if (maxMembers < currentMembers) {
            rspData["success"] = false;
            rspData["error"] = QStringLiteral("当前房间人数已超过新上限，禁止修改人数上限");
            rspData["currentMembers"] = currentMembers;
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }

        QJsonObject cleanupSummary;
        QList<int> cleanupIds = buildCleanupPlan(roomId, maxFileSize, totalFileSpace, maxFileCount, &cleanupSummary);
        bool forceCleanup = data["forceCleanup"].toBool(false);

        if (!cleanupIds.isEmpty() && !forceCleanup) {
            rspData["success"] = false;
            rspData["needConfirm"] = true;
            rspData["error"] = QStringLiteral("调整后需要清理部分历史文件");
            rspData["maxFileSize"] = static_cast<double>(maxFileSize);
            rspData["totalFileSpace"] = static_cast<double>(totalFileSpace);
            rspData["maxFileCount"] = maxFileCount;
            rspData["maxMembers"] = maxMembers;
            rspData["cleanupSummary"] = cleanupSummary;
            session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
            return;
        }

        QJsonArray clearedIds;
        if (!cleanupIds.isEmpty()) {
            if (!applyFileCleanupPlan(roomId, cleanupIds, QStringLiteral("文件已过期或被清除"), &clearedIds)) {
                rspData["success"] = false;
                rspData["error"] = QStringLiteral("清理历史文件失败，请稍后重试");
                session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));
                return;
            }
        }

        m_db->setRoomSettings(roomId, maxFileSize, totalFileSpace, maxFileCount, maxMembers);

        rspData["success"] = true;
        rspData["maxFileSize"] = static_cast<double>(maxFileSize);
        rspData["totalFileSpace"] = static_cast<double>(totalFileSpace);
        rspData["maxFileCount"] = maxFileCount;
        rspData["maxMembers"] = maxMembers;
        rspData["clearedFileIds"] = clearedIds;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_RSP, rspData));

        // 通知房间所有人
        QJsonObject notifyData;
        notifyData["roomId"] = roomId;
        notifyData["maxFileSize"] = static_cast<double>(maxFileSize);
        notifyData["totalFileSpace"] = static_cast<double>(totalFileSpace);
        notifyData["maxFileCount"] = maxFileCount;
        notifyData["maxMembers"] = maxMembers;
        notifyData["clearedFileIds"] = clearedIds;
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_NOTIFY, notifyData));

        // 系统消息
        broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
            {{"roomId", roomId}, {"content", QString("%1 更新了房间限制：单文件%2MB，总空间%3GB，文件数%4，人数%5")
                .arg(session->displayName())
                .arg(maxFileSize / 1024 / 1024)
                .arg(totalFileSpace / 1024 / 1024 / 1024)
                .arg(maxFileCount)
                .arg(maxMembers)}}));
        if (!clearedIds.isEmpty()) {
            broadcastToRoom(roomId, Protocol::makeMessage(Protocol::MsgType::SYSTEM_MSG,
                {{"roomId", roomId}, {"content", QString("因新限制生效，已清理 %1 个历史文件（记录保留，文件状态变为过期/已清除）").arg(clearedIds.size())}}));
        }
    } else {
        // 查询设置
        QJsonObject settings = m_db->getRoomSettings(roomId);
        rspData["success"] = true;
        rspData["maxFileSize"] = settings["maxFileSize"];
        rspData["totalFileSpace"] = settings["totalFileSpace"];
        rspData["maxFileCount"] = settings["maxFileCount"];
        rspData["maxMembers"] = settings["maxMembers"];
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

    // 在 CASCADE 删除前查询该房间所有 COS 文件 URL
    const QStringList roomCosUrls = m_db->getCosUrlsForRoom(roomId);

    // 从数据库删除（CASCADE 会清理 room_members, messages, files, room_admins, room_settings）
    if (m_db->deleteRoom(roomId)) {
        // 从内存缓存中移除
        m_roomMgr->removeRoom(roomId);
        deleteCosFiles(roomCosUrls);

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
    QString validationError;

    if (!InputValidator::validatePassword(oldPassword, &validationError, false)
        || !InputValidator::validatePassword(newPassword, &validationError)) {
        rspData["success"] = false;
        rspData["error"] = validationError;
    } else {
        if (!allowAuthenticationAttempt(session, session->username(),
                                        QStringLiteral("change-password"),
                                        Protocol::MsgType::CHANGE_PASSWORD_RSP)) {
            return;
        }
        bool ok = m_db->changePassword(session->userId(), oldPassword, newPassword);
        if (ok) {
            m_authAbuseGuard.recordSuccess(session->username());
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
        // 检查是否是对方已发来请求的情况
        QJsonArray pending = m_db->getPendingFriendRequests(session->userId());
        bool hasReverse = false;
        for (const QJsonValue &v : pending) {
            if (v.toObject()["fromUsername"].toString() == targetUsername) {
                hasReverse = true;
                break;
            }
        }
        rspData["success"] = false;
        rspData["error"]   = hasReverse
            ? QStringLiteral("对方已向你发送了好友申请，请在好友申请中处理")
            : QStringLiteral("已有待处理的好友请求");
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
    QString fromUsername = m_db->getPendingFriendRequestSender(requestId, session->userId());
    QJsonObject rspData;

    if (!fromUsername.isEmpty() && m_db->acceptFriendRequest(requestId, session->userId())) {
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
    if (friendUsername == session->username()) {
        QJsonObject rspData;
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("不能删除自己");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_REMOVE_RSP, rspData));
        return;
    }

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

    // 添加在线状态和未读计数
    {
        QMutexLocker locker(&m_mutex);
        for (int i = 0; i < friends.size(); ++i) {
            QJsonObject fr = friends[i].toObject();
            fr["isOnline"] = m_sessions.contains(fr["username"].toString());
            fr["unread"] = m_db->getUnreadFriendCount(fr["friendshipId"].toInt(), session->userId());
            friends[i] = fr;
        }
    }

    QJsonObject rspData;
    rspData["friends"] = friends;
    rspData["pendingFriendRequests"] = m_db->getPendingFriendRequestCount(session->userId());
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

    const QJsonObject data = msg["data"].toObject();
    const QString friendUsername = data["friendUsername"].toString();
    const QString content = data["content"].toString();
    const QString contentType = data["contentType"].toString("text");
    const QString clientMessageId = data["clientMessageId"].toString().isEmpty()
                                        ? msg["id"].toString()
                                        : data["clientMessageId"].toString();

    FriendMessageService::Command command;
    command.senderId = session->userId();
    command.friendUsername = friendUsername;
    command.clientMessageId = clientMessageId;
    command.content = content;
    command.contentType = contentType;
    const FriendMessageService::Result result = m_friendMessageService.submit(command);
    recordFriendMessageOutcome(result.status, session->userId(), result.friendshipId);

    const bool accepted = result.status == FriendMessageService::Status::Accepted;
    const bool duplicate = result.status == FriendMessageService::Status::Duplicate;
    QJsonObject responseData;
    responseData["success"] = accepted || duplicate;
    responseData["friendUsername"] = friendUsername;
    if (!clientMessageId.isEmpty() && clientMessageId.toUtf8().size() <= 128)
        responseData["clientMessageId"] = clientMessageId;
    if (accepted || duplicate) {
        responseData["friendshipId"] = result.friendshipId;
        responseData["id"] = result.messageId;
        responseData["sequence"] = static_cast<double>(result.sequence);
        responseData["timestamp"] = static_cast<double>(result.createdAtMs);
        responseData["duplicate"] = duplicate;
    } else {
        responseData["errorCode"] = result.errorCode;
        responseData["error"] = result.error;
        qWarning().noquote()
            << QStringLiteral("[Messaging] friend-send rejected userId=%1 friendshipId=%2 code=%3")
                   .arg(session->userId())
                   .arg(result.friendshipId)
                   .arg(result.errorCode);
    }
    session->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FRIEND_CHAT_SEND_RSP, responseData));
    if (!accepted) return;

    QJsonObject chatData;
    chatData["id"]           = result.messageId;
    chatData["friendshipId"] = result.friendshipId;
    chatData["sequence"]     = static_cast<double>(result.sequence);
    chatData["clientMessageId"] = clientMessageId;
    chatData["sender"]       = session->username();
    chatData["senderName"]   = session->displayName();
    chatData["friendUsername"] = friendUsername;
    chatData["content"]      = content;
    chatData["contentType"]  = contentType;
    chatData["timestamp"]    = static_cast<double>(result.createdAtMs);

    QJsonObject chatMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_CHAT_MSG, chatData);

    // 发送给双方
    session->sendMessage(chatMsg);
    if (friendUsername != session->username()) {
        sendToUser(friendUsername, chatMsg);
    }
}

void ChatServer::handleFriendHistory(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString friendUsername = data["friendUsername"].toString();
    int count = InputValidator::boundedHistoryCount(data["count"].toInt(50));
    const qint64 before = static_cast<qint64>(data["before"].toDouble(0));
    const bool sequenceMode = data.contains("afterSequence");
    const qint64 afterSequence = static_cast<qint64>(data["afterSequence"].toDouble(0));

    QJsonObject rspData;
    rspData["friendUsername"] = friendUsername;
    if (sequenceMode && afterSequence < 0) {
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("INVALID_SEQUENCE_CURSOR");
        rspData["error"] = QStringLiteral("消息序列游标无效");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_RSP, rspData));
        return;
    }

    int friendId = m_db->getUserIdByName(friendUsername);
    int friendshipId = friendId > 0 ? m_db->getFriendshipId(session->userId(), friendId) : -1;
    if (friendshipId < 0) {
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("FRIENDSHIP_ACCESS_DENIED");
        rspData["error"] = QStringLiteral("无权读取该会话历史");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_RSP, rspData));
        return;
    }

    QJsonArray messages = sequenceMode
                              ? m_db->getFriendMessageHistoryAfterSequence(
                                    friendshipId, count, afterSequence)
                              : m_db->getFriendMessageHistory(friendshipId, count, before);

    rspData["success"] = true;
    rspData["friendshipId"]  = friendshipId;
    rspData["messages"]      = messages;
    if (sequenceMode) {
        const qint64 lastSequence = m_db->getFriendshipLastMessageSequence(friendshipId);
        qint64 nextSequence = lastSequence;
        if (messages.size() == count && !messages.isEmpty())
            nextSequence = static_cast<qint64>(messages.last().toObject()["syncSequence"].toDouble());
        const bool hasMore = nextSequence < lastSequence;
        rspData["mode"] = QStringLiteral("sequence");
        rspData["nextSequence"] = static_cast<double>(nextSequence);
        rspData["lastSequence"] = static_cast<double>(lastSequence);
        rspData["hasMore"] = hasMore;
    }
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_RSP, rspData));
}

void ChatServer::handleFriendFileSend(ClientSession *session, const QJsonObject &msg) {
    if (!session->isAuthenticated()) return;

    QJsonObject data = msg["data"].toObject();
    QString friendUsername = data["friendUsername"].toString();
    QString fileName  = data["fileName"].toString();
    qint64 fileSize   = static_cast<qint64>(data["fileSize"].toDouble());
    QString fileData  = data["fileData"].toString();
    QString thumbnail = data["thumbnail"].toString();

    int friendId = m_db->getUserIdByName(friendUsername);
    if (friendId < 0) return;
    int friendshipId = m_db->getFriendshipId(session->userId(), friendId);
    if (friendshipId < 0) return;

    QByteArray rawData;
    QString validationError;
    QString validatedFileName;
    if (!InputValidator::validateFileName(fileName, &validatedFileName, &validationError)
        || !InputValidator::decodeInlineFile(fileData, fileSize, Protocol::MAX_SMALL_FILE,
                                             &rawData, &validationError)) {
        QJsonObject rsp;
        rsp["success"] = false;
        rsp["error"] = validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, rsp));
        return;
    }
    fileName = validatedFileName;

    // 根据文件后缀确定 contentType（与房间 handleFileSend 一致）
    QString contentType = QStringLiteral("file");
    QString typeDir = fileTypeSubDir(fileName);
    if (typeDir == QLatin1String("Image"))
        contentType = QStringLiteral("image");
    else if (typeDir == QLatin1String("Video"))
        contentType = QStringLiteral("video");

    // 保存文件
    QString targetDir = friendFileDir(friendshipId, fileName);
    QString safeName = QString::number(QDateTime::currentMSecsSinceEpoch()) + "_" + fileName;
    QString filePath = targetDir + "/" + safeName;

    QFile f(filePath);
    if (!f.open(QIODevice::WriteOnly)) return;
    if (f.write(rawData) != rawData.size()) {
        f.close();
        QFile::remove(filePath);
        QJsonObject rsp;
        rsp["success"] = false;
        rsp["error"] = QStringLiteral("服务器写入文件失败");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, rsp));
        return;
    }
    f.close();

    int fileId = m_db->saveFriendFile(friendshipId, session->userId(), fileName, filePath, fileSize);
    if (fileId <= 0) {
        QFile::remove(filePath);
        return;
    }

    // 图片自动生成缩略图（与房间 handleFileSend 一致）
#ifndef CHATROOM_DISABLE_IMAGE_THUMBNAILS
    if (contentType == QLatin1String("image") && fileSize < 20 * 1024 * 1024) {
        QImage img(filePath);
        if (!img.isNull()) {
            QImage thumb = img.scaled(200, 200, Qt::KeepAspectRatio, Qt::FastTransformation);
            QByteArray thumbData;
            QBuffer buf(&thumbData);
            buf.open(QIODevice::WriteOnly);
            thumb.save(&buf, "JPEG", 60);
            QString serverThumb = QString::fromLatin1(thumbData.toBase64());
            if (!serverThumb.isEmpty()) thumbnail = serverThumb;
        }
    }
#endif
    // QImage 失败或非图片类型时，使用客户端提供的缩略图（视频缩略图由客户端生成）
    if (thumbnail.isEmpty() && data.contains("thumbnail")) {
        thumbnail = data["thumbnail"].toString();
    }

    qint64 sequence = 0;
    qint64 timestamp = 0;
    int msgId  = m_db->saveFriendMessage(
        friendshipId, session->userId(), fileName, contentType,
        fileName, fileSize, fileId, thumbnail, &sequence, &timestamp);
    if (msgId <= 0) {
        m_db->deleteStoredFileRecord(fileId, true);
        QFile::remove(filePath);
        return;
    }

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
    notifyData["sequence"]     = static_cast<double>(sequence);
    notifyData["timestamp"]    = static_cast<double>(timestamp);
    if (!thumbnail.isEmpty()) notifyData["thumbnail"] = thumbnail;

    QJsonObject notifyMsg = Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_NOTIFY, notifyData);
    session->sendMessage(notifyMsg);
    if (friendUsername != session->username()) {
        sendToUser(friendUsername, notifyMsg);
    }
}

void ChatServer::handleFriendFileUploadStart(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    QString friendUsername = data["friendUsername"].toString();
    QString fileName = data["fileName"].toString();
    qint64 fileSize  = static_cast<qint64>(data["fileSize"].toDouble());
    const QString clientMessageId = data["clientMessageId"].toString();

    int friendId = m_db->getUserIdByName(friendUsername);
    QJsonObject rspData;
    if (!validOptionalClientMessageId(clientMessageId)) {
        rspData["success"] = false;
        rspData["errorCode"] = QStringLiteral("INVALID_CLIENT_MESSAGE_ID");
        rspData["error"] = QStringLiteral("客户端消息 ID 过长");
        session->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }

    QString validationError;
    QString validatedFileName;
    if (!InputValidator::validateFileName(fileName, &validatedFileName, &validationError)
        || fileSize <= 0) {
        rspData["success"] = false;
        rspData["error"] = validationError.isEmpty()
            ? QStringLiteral("文件大小无效") : validationError;
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
        return;
    }
    fileName = validatedFileName;

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

    // 好友文件上限 100MB
    if (fileSize > Protocol::MAX_FRIEND_FILE) {
        rspData["success"] = false;
        rspData["error"]   = QStringLiteral("文件超过好友传输限制(100MB)");
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
    state.clientMessageId = clientMessageId;
    state.fileName    = fileName;
    state.filePath    = filePath;
    state.fileSize    = fileSize;
    state.received    = 0;
    state.file        = file;
    m_uploads[uploadId] = state;

    rspData["success"]        = true;
    rspData["uploadId"]       = uploadId;
    if (!clientMessageId.isEmpty()) rspData["clientMessageId"] = clientMessageId;
    rspData["httpUploadPath"] = QStringLiteral("/api/upload/%1").arg(uploadId);
    rspData["friendUsername"]  = friendUsername;
    rspData["friendshipId"]   = friendshipId;
    session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_UPLOAD_START_RSP, rspData));
}

void ChatServer::handleFriendRecall(ClientSession *session, const QJsonObject &data) {
    if (!session->isAuthenticated()) return;

    int messageId = data["messageId"].toInt();
    const int friendshipId = m_db->getFriendshipIdForOwnedMessage(messageId, session->userId());
    const QString friendUsername = friendshipId > 0
        ? m_db->getOtherFriendUsername(friendshipId, session->userId())
        : QString();

    QJsonObject rspData;
    rspData["messageId"] = messageId;
    rspData["friendUsername"] = friendUsername;

    RecallResult recall;
    if (friendshipId > 0 && !friendUsername.isEmpty()) {
        recall = m_db->recallFriendMessage(messageId, session->userId(),
                                           Protocol::RECALL_TIME_LIMIT_SEC);
    } else {
        recall.status = RecallResult::Status::Rejected;
    }
    const bool accepted = recall.status == RecallResult::Status::Applied ||
                          recall.status == RecallResult::Status::Duplicate;
    if (accepted) {
        // 清理服务器文件
        auto fileInfo = m_db->getFileInfoForFriendMessage(messageId);
        if (fileInfo.first > 0) {
            const QString cosUrl = m_db->getCosUrl(fileInfo.first, true);
            if (!fileInfo.second.isEmpty()) {
                QFile::remove(fileInfo.second);
                qInfo() << "[Server] 好友撤回消息，已删除文件:" << fileInfo.second;
            }
            m_db->deleteStoredFileRecord(fileInfo.first, true);
            if (!cosUrl.isEmpty())
                m_cos->deleteCosFile(cosUrl);
        }

        rspData["success"] = true;
        rspData["duplicate"] = recall.status == RecallResult::Status::Duplicate;
        if (recall.mutationSequence > 0)
            rspData["mutationSequence"] = static_cast<double>(recall.mutationSequence);
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_RSP, rspData));

        // 通知对方
        if (recall.status == RecallResult::Status::Applied) {
            QMutexLocker locker(&m_mutex);
            if (friendUsername != session->username() && m_sessions.contains(friendUsername)) {
                QJsonObject notifyData;
                notifyData["messageId"] = messageId;
                notifyData["friendUsername"] = session->username();
                notifyData["mutationSequence"] =
                    static_cast<double>(recall.mutationSequence);
                m_sessions[friendUsername]->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_NOTIFY, notifyData));
            }
        }
    } else {
        rspData["success"] = false;
        rspData["errorCode"] = recall.status == RecallResult::Status::Failed
            ? QStringLiteral("FRIEND_RECALL_PERSISTENCE_FAILED")
            : QStringLiteral("FRIEND_RECALL_REJECTED");
        rspData["error"]   = QStringLiteral("无法撤回（超时或非本人消息）");
        session->sendMessage(Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_RSP, rspData));
    }
}
