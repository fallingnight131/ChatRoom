#pragma once

#include <QString>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonDocument>
#include <QByteArray>
#include <QDataStream>
#include <QIODevice>
#include <QDateTime>
#include <QUuid>

namespace Protocol {

// ==================== 协议版本 ====================
constexpr quint16 VERSION = 1;
constexpr quint16 DEFAULT_PORT = 9527;
constexpr int HEARTBEAT_INTERVAL_MS  = 30000;   // 30秒心跳
constexpr int HEARTBEAT_TIMEOUT_MS   = 90000;   // 90秒超时
constexpr int RECONNECT_INTERVAL_MS  = 5000;    // 5秒重连
constexpr int RECALL_TIME_LIMIT_SEC  = 120;     // 2分钟撤回限制
constexpr int FILE_CHUNK_SIZE        = 4 * 1024 * 1024; // 4MB 分块（base64 后 ~5.3MB）
constexpr qint64 MAX_SMALL_FILE      = 8 * 1024 * 1024; // <=8MB 走老协议直传
constexpr qint64 MAX_LARGE_FILE      = 4LL * 1024 * 1024 * 1024; // 4GB 上限

// ==================== 消息类型 ====================
namespace MsgType {
    // 认证
    inline const QString LOGIN_REQ        = QStringLiteral("LOGIN_REQ");
    inline const QString LOGIN_RSP        = QStringLiteral("LOGIN_RSP");
    inline const QString REGISTER_REQ     = QStringLiteral("REGISTER_REQ");
    inline const QString REGISTER_RSP     = QStringLiteral("REGISTER_RSP");
    inline const QString LOGOUT           = QStringLiteral("LOGOUT");

    // 聊天消息
    inline const QString CHAT_MSG         = QStringLiteral("CHAT_MSG");
    inline const QString SYSTEM_MSG       = QStringLiteral("SYSTEM_MSG");

    // 房间管理
    inline const QString CREATE_ROOM_REQ  = QStringLiteral("CREATE_ROOM_REQ");
    inline const QString CREATE_ROOM_RSP  = QStringLiteral("CREATE_ROOM_RSP");
    inline const QString JOIN_ROOM_REQ    = QStringLiteral("JOIN_ROOM_REQ");
    inline const QString JOIN_ROOM_RSP    = QStringLiteral("JOIN_ROOM_RSP");
    inline const QString LEAVE_ROOM       = QStringLiteral("LEAVE_ROOM");
    inline const QString ROOM_LIST_REQ    = QStringLiteral("ROOM_LIST_REQ");
    inline const QString ROOM_LIST_RSP    = QStringLiteral("ROOM_LIST_RSP");
    inline const QString USER_LIST_REQ    = QStringLiteral("USER_LIST_REQ");
    inline const QString USER_LIST_RSP    = QStringLiteral("USER_LIST_RSP");

    // 消息历史
    inline const QString HISTORY_REQ      = QStringLiteral("HISTORY_REQ");
    inline const QString HISTORY_RSP      = QStringLiteral("HISTORY_RSP");

    // 文件传输（小文件直传）
    inline const QString FILE_SEND        = QStringLiteral("FILE_SEND");
    inline const QString FILE_NOTIFY      = QStringLiteral("FILE_NOTIFY");
    inline const QString FILE_DOWNLOAD_REQ= QStringLiteral("FILE_DOWNLOAD_REQ");
    inline const QString FILE_DOWNLOAD_RSP= QStringLiteral("FILE_DOWNLOAD_RSP");

    // 大文件分块传输
    inline const QString FILE_UPLOAD_START  = QStringLiteral("FILE_UPLOAD_START");
    inline const QString FILE_UPLOAD_START_RSP = QStringLiteral("FILE_UPLOAD_START_RSP");
    inline const QString FILE_UPLOAD_CHUNK  = QStringLiteral("FILE_UPLOAD_CHUNK");
    inline const QString FILE_UPLOAD_CHUNK_RSP = QStringLiteral("FILE_UPLOAD_CHUNK_RSP");
    inline const QString FILE_UPLOAD_END   = QStringLiteral("FILE_UPLOAD_END");
    inline const QString FILE_DOWNLOAD_CHUNK_REQ = QStringLiteral("FILE_DOWNLOAD_CHUNK_REQ");
    inline const QString FILE_DOWNLOAD_CHUNK_RSP = QStringLiteral("FILE_DOWNLOAD_CHUNK_RSP");

    // 消息撤回
    inline const QString RECALL_REQ       = QStringLiteral("RECALL_REQ");
    inline const QString RECALL_RSP       = QStringLiteral("RECALL_RSP");
    inline const QString RECALL_NOTIFY    = QStringLiteral("RECALL_NOTIFY");

    // 心跳
    inline const QString HEARTBEAT        = QStringLiteral("HEARTBEAT");
    inline const QString HEARTBEAT_ACK    = QStringLiteral("HEARTBEAT_ACK");

    // 通知
    inline const QString USER_JOINED      = QStringLiteral("USER_JOINED");
    inline const QString USER_LEFT        = QStringLiteral("USER_LEFT");
    inline const QString FORCE_OFFLINE    = QStringLiteral("FORCE_OFFLINE");

    // 管理员功能
    inline const QString SET_ADMIN_REQ    = QStringLiteral("SET_ADMIN_REQ");
    inline const QString SET_ADMIN_RSP    = QStringLiteral("SET_ADMIN_RSP");
    inline const QString ADMIN_STATUS     = QStringLiteral("ADMIN_STATUS");     // 通知用户管理员状态
    inline const QString DELETE_MSGS_REQ  = QStringLiteral("DELETE_MSGS_REQ");  // 删除消息
    inline const QString DELETE_MSGS_RSP  = QStringLiteral("DELETE_MSGS_RSP");
    inline const QString DELETE_MSGS_NOTIFY = QStringLiteral("DELETE_MSGS_NOTIFY"); // 通知其他人

    // 房间设置
    inline const QString ROOM_SETTINGS_REQ  = QStringLiteral("ROOM_SETTINGS_REQ");
    inline const QString ROOM_SETTINGS_RSP  = QStringLiteral("ROOM_SETTINGS_RSP");
    inline const QString ROOM_SETTINGS_NOTIFY = QStringLiteral("ROOM_SETTINGS_NOTIFY");

    // 头像
    inline const QString AVATAR_UPLOAD_REQ  = QStringLiteral("AVATAR_UPLOAD_REQ");
    inline const QString AVATAR_UPLOAD_RSP  = QStringLiteral("AVATAR_UPLOAD_RSP");
    inline const QString AVATAR_GET_REQ     = QStringLiteral("AVATAR_GET_REQ");
    inline const QString AVATAR_GET_RSP     = QStringLiteral("AVATAR_GET_RSP");
    inline const QString AVATAR_UPDATE_NOTIFY = QStringLiteral("AVATAR_UPDATE_NOTIFY");
}

// ==================== 数据包帧: [4字节长度][JSON数据] ====================

/// 将 JSON 对象打包为带长度前缀的二进制帧
inline QByteArray pack(const QJsonObject &msg) {
    QByteArray json = QJsonDocument(msg).toJson(QJsonDocument::Compact);
    QByteArray packet;
    packet.reserve(4 + json.size());
    QDataStream stream(&packet, QIODevice::WriteOnly);
    stream.setByteOrder(QDataStream::BigEndian);
    stream << static_cast<quint32>(json.size());
    packet.append(json);
    return packet;
}

/// 从缓冲区中尝试解析一个完整的 JSON 消息
/// @return true 如果成功提取了一条消息（同时从 buffer 中移除已读字节）
inline bool unpack(QByteArray &buffer, QJsonObject &msg) {
    if (buffer.size() < 4)
        return false;

    QDataStream stream(buffer.left(4));
    stream.setByteOrder(QDataStream::BigEndian);
    quint32 len = 0;
    stream >> len;

    if (len > 50 * 1024 * 1024) { // 单消息上限 50MB（支持大块传输）
        buffer.clear();
        return false;
    }

    if (static_cast<quint32>(buffer.size()) < 4 + len)
        return false;

    QByteArray json = buffer.mid(4, static_cast<int>(len));
    buffer.remove(0, 4 + static_cast<int>(len));

    QJsonParseError err;
    QJsonDocument doc = QJsonDocument::fromJson(json, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject())
        return false;

    msg = doc.object();
    return true;
}

// ==================== 辅助构造函数 ====================

/// 创建协议消息
inline QJsonObject makeMessage(const QString &type, const QJsonObject &data = {}) {
    QJsonObject msg;
    msg["type"]      = type;
    msg["id"]        = QUuid::createUuid().toString(QUuid::WithoutBraces);
    msg["timestamp"] = QDateTime::currentMSecsSinceEpoch();
    msg["data"]      = data;
    return msg;
}

/// 快捷创建登录请求
inline QJsonObject makeLoginReq(const QString &username, const QString &password) {
    QJsonObject data;
    data["username"] = username;
    data["password"] = password;
    return makeMessage(MsgType::LOGIN_REQ, data);
}

/// 快捷创建注册请求
inline QJsonObject makeRegisterReq(const QString &username, const QString &password) {
    QJsonObject data;
    data["username"] = username;
    data["password"] = password;
    return makeMessage(MsgType::REGISTER_REQ, data);
}

/// 快捷创建聊天消息
inline QJsonObject makeChatMsg(int roomId, const QString &sender, const QString &content,
                                const QString &contentType = "text") {
    QJsonObject data;
    data["roomId"]       = roomId;
    data["sender"]       = sender;
    data["content"]      = content;
    data["contentType"]  = contentType; // "text", "emoji", "image", "file"
    return makeMessage(MsgType::CHAT_MSG, data);
}

/// 快捷创建系统消息
inline QJsonObject makeSystemMsg(int roomId, const QString &content) {
    QJsonObject data;
    data["roomId"]  = roomId;
    data["content"] = content;
    return makeMessage(MsgType::SYSTEM_MSG, data);
}

/// 快捷创建房间操作
inline QJsonObject makeCreateRoomReq(const QString &roomName) {
    QJsonObject data;
    data["roomName"] = roomName;
    return makeMessage(MsgType::CREATE_ROOM_REQ, data);
}

inline QJsonObject makeJoinRoomReq(int roomId) {
    QJsonObject data;
    data["roomId"] = roomId;
    return makeMessage(MsgType::JOIN_ROOM_REQ, data);
}

inline QJsonObject makeLeaveRoom(int roomId) {
    QJsonObject data;
    data["roomId"] = roomId;
    return makeMessage(MsgType::LEAVE_ROOM, data);
}

inline QJsonObject makeHistoryReq(int roomId, int count = 50, qint64 beforeTimestamp = 0) {
    QJsonObject data;
    data["roomId"] = roomId;
    data["count"]  = count;
    if (beforeTimestamp > 0)
        data["before"] = beforeTimestamp;
    return makeMessage(MsgType::HISTORY_REQ, data);
}

inline QJsonObject makeRecallReq(int messageId, int roomId) {
    QJsonObject data;
    data["messageId"] = messageId;
    data["roomId"]    = roomId;
    return makeMessage(MsgType::RECALL_REQ, data);
}

inline QJsonObject makeHeartbeat() {
    return makeMessage(MsgType::HEARTBEAT);
}

inline QJsonObject makeHeartbeatAck() {
    return makeMessage(MsgType::HEARTBEAT_ACK);
}

} // namespace Protocol
