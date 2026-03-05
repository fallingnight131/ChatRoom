#include "DatabaseManager.h"

#include <QSqlQuery>
#include <QSqlError>
#include <QCryptographicHash>
#include <QUuid>
#include <QThread>
#include <QDebug>
#include <QDateTime>
#include <QJsonObject>
#include <QCoreApplication>
#include <QDir>

DatabaseManager::DatabaseManager(QObject *parent)
    : QObject(parent)
{
    // 默认将数据库文件放在可执行文件同目录
    m_dbPath = QCoreApplication::applicationDirPath() + "/chatroom.db";

    // 可通过环境变量覆盖路径
    if (qEnvironmentVariableIsSet("CHATROOM_DB_PATH"))
        m_dbPath = qEnvironmentVariable("CHATROOM_DB_PATH");
}

DatabaseManager::~DatabaseManager() = default;

QSqlDatabase DatabaseManager::getConnection() {
    QString connName = QStringLiteral("chatroom_conn_%1")
                           .arg(reinterpret_cast<quintptr>(QThread::currentThreadId()));

    if (QSqlDatabase::contains(connName)) {
        QSqlDatabase db = QSqlDatabase::database(connName);
        if (db.isOpen()) return db;
        db.open();
        return db;
    }

    QSqlDatabase db = QSqlDatabase::addDatabase("QSQLITE", connName);
    db.setDatabaseName(m_dbPath);

    if (!db.open()) {
        qCritical() << "[DB] SQLite 打开失败:" << db.lastError().text();
    } else {
        // 启用 WAL 模式以提高并发性能，启用外键约束
        QSqlQuery q(db);
        q.exec("PRAGMA journal_mode=WAL");
        q.exec("PRAGMA foreign_keys=ON");
    }
    return db;
}

bool DatabaseManager::initialize() {
    QMutexLocker locker(&m_initMutex);
    if (m_initialized) return true;

    QSqlDatabase db = getConnection();
    if (!db.isOpen()) return false;

    QSqlQuery q(db);

    // 创建用户表
    q.exec("CREATE TABLE IF NOT EXISTS users ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  username TEXT UNIQUE NOT NULL,"
           "  password_hash TEXT NOT NULL,"
           "  salt TEXT NOT NULL,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
           ")");

    if (q.lastError().isValid())
        qWarning() << "[DB] 创建 users 表错误:" << q.lastError().text();

    // 创建房间表
    q.exec("CREATE TABLE IF NOT EXISTS rooms ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  name TEXT NOT NULL,"
           "  creator_id INTEGER NOT NULL,"
           "  password TEXT DEFAULT NULL,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (creator_id) REFERENCES users(id)"
           ")");

    // 房间成员表
    q.exec("CREATE TABLE IF NOT EXISTS room_members ("
           "  room_id INTEGER NOT NULL,"
           "  user_id INTEGER NOT NULL,"
           "  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  PRIMARY KEY (room_id, user_id),"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 消息表
    q.exec("CREATE TABLE IF NOT EXISTS messages ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  room_id INTEGER NOT NULL,"
           "  user_id INTEGER NOT NULL,"
           "  content TEXT,"
           "  content_type TEXT DEFAULT 'text',"
           "  file_name TEXT DEFAULT '',"
           "  file_size INTEGER DEFAULT 0,"
           "  file_id INTEGER DEFAULT 0,"
           "  recalled INTEGER DEFAULT 0,"
           "  thumbnail TEXT DEFAULT '',"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 存量数据库添加 thumbnail 列
    q.exec("ALTER TABLE messages ADD COLUMN thumbnail TEXT DEFAULT ''");

    // 消息索引
    q.exec("CREATE INDEX IF NOT EXISTS idx_msg_room_time ON messages(room_id, created_at)");

    // 文件表
    q.exec("CREATE TABLE IF NOT EXISTS files ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  room_id INTEGER NOT NULL,"
           "  user_id INTEGER NOT NULL,"
           "  file_name TEXT NOT NULL,"
           "  file_path TEXT NOT NULL,"
           "  file_size INTEGER DEFAULT 0,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 房间管理员表
    q.exec("CREATE TABLE IF NOT EXISTS room_admins ("
           "  room_id INTEGER NOT NULL,"
           "  user_id INTEGER NOT NULL,"
           "  PRIMARY KEY (room_id, user_id),"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 房间设置表
    q.exec("CREATE TABLE IF NOT EXISTS room_settings ("
           "  room_id INTEGER PRIMARY KEY,"
           "  max_file_size INTEGER DEFAULT 4294967296,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE"
           ")");

    // 用户头像表
    q.exec("CREATE TABLE IF NOT EXISTS user_avatars ("
           "  user_id INTEGER PRIMARY KEY,"
           "  avatar_data BLOB,"
           "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 聊天室头像表
    q.exec("CREATE TABLE IF NOT EXISTS room_avatars ("
           "  room_id INTEGER PRIMARY KEY,"
           "  avatar_data BLOB,"
           "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE"
           ")");

    // === 数据库迁移：添加 display_name 列 ===
    {
        // 检测 display_name 列是否存在
        QSqlQuery chk(db);
        chk.exec("PRAGMA table_info(users)");
        bool hasDisplayName = false;
        while (chk.next()) {
            if (chk.value(1).toString() == "display_name") {
                hasDisplayName = true;
                break;
            }
        }
        if (!hasDisplayName) {
            q.exec("ALTER TABLE users ADD COLUMN display_name TEXT DEFAULT ''");
            if (q.lastError().isValid())
                qWarning() << "[DB] 添加 display_name 列失败:" << q.lastError().text();
            else {
                // 将已有用户的 display_name 设置为 username
                q.exec("UPDATE users SET display_name = username WHERE display_name = '' OR display_name IS NULL");
                qInfo() << "[DB] 迁移: 已添加 display_name 列并初始化";
            }
        }
    }

    // === 数据库迁移：添加 last_uid_change 列 ===
    {
        QSqlQuery chk(db);
        chk.exec("PRAGMA table_info(users)");
        bool hasLastUidChange = false;
        while (chk.next()) {
            if (chk.value(1).toString() == "last_uid_change") {
                hasLastUidChange = true;
                break;
            }
        }
        if (!hasLastUidChange) {
            q.exec("ALTER TABLE users ADD COLUMN last_uid_change TIMESTAMP DEFAULT NULL");
            if (q.lastError().isValid())
                qWarning() << "[DB] 添加 last_uid_change 列失败:" << q.lastError().text();
            else
                qInfo() << "[DB] 迁移: 已添加 last_uid_change 列";
        }
    }

    m_initialized = true;

    // 好友请求表
    q.exec("CREATE TABLE IF NOT EXISTS friend_requests ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  from_user_id INTEGER NOT NULL,"
           "  to_user_id INTEGER NOT NULL,"
           "  status TEXT DEFAULT 'pending',"  // pending, accepted, rejected
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 好友关系表
    q.exec("CREATE TABLE IF NOT EXISTS friendships ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  user_id1 INTEGER NOT NULL,"
           "  user_id2 INTEGER NOT NULL,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  UNIQUE(user_id1, user_id2),"
           "  FOREIGN KEY (user_id1) REFERENCES users(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id2) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    // 好友私聊消息表
    q.exec("CREATE TABLE IF NOT EXISTS friend_messages ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  friendship_id INTEGER NOT NULL,"
           "  sender_id INTEGER NOT NULL,"
           "  content TEXT,"
           "  content_type TEXT DEFAULT 'text',"
           "  file_name TEXT DEFAULT '',"
           "  file_size INTEGER DEFAULT 0,"
           "  file_id INTEGER DEFAULT 0,"
           "  recalled INTEGER DEFAULT 0,"
           "  thumbnail TEXT DEFAULT '',"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (friendship_id) REFERENCES friendships(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");
    q.exec("CREATE INDEX IF NOT EXISTS idx_friend_msg_time ON friend_messages(friendship_id, created_at)");

    // 好友文件表
    q.exec("CREATE TABLE IF NOT EXISTS friend_files ("
           "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
           "  friendship_id INTEGER NOT NULL,"
           "  user_id INTEGER NOT NULL,"
           "  file_name TEXT NOT NULL,"
           "  file_path TEXT NOT NULL,"
           "  file_size INTEGER DEFAULT 0,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (friendship_id) REFERENCES friendships(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

    qInfo() << "[DB] SQLite 数据库初始化完成，路径:" << m_dbPath;
    return true;
}

// ==================== 用户管理 ====================

QString DatabaseManager::generateSalt() {
    return QUuid::createUuid().toString(QUuid::WithoutBraces).left(16);
}

QString DatabaseManager::hashPassword(const QString &password, const QString &salt) {
    QByteArray data = (password + salt).toUtf8();
    return QCryptographicHash::hash(data, QCryptographicHash::Sha256).toHex();
}

int DatabaseManager::registerUser(const QString &uniqueId, const QString &displayName, const QString &password) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 检查重名
    q.prepare("SELECT id FROM users WHERE username = ?");
    q.addBindValue(uniqueId);
    q.exec();
    if (q.next()) return -1;

    QString salt = generateSalt();
    QString hash = hashPassword(password, salt);

    q.prepare("INSERT INTO users (username, display_name, password_hash, salt) VALUES (?, ?, ?, ?)");
    q.addBindValue(uniqueId);
    q.addBindValue(displayName);
    q.addBindValue(hash);
    q.addBindValue(salt);

    if (q.exec())
        return q.lastInsertId().toInt();

    qWarning() << "[DB] 注册失败:" << q.lastError().text();
    return -1;
}

int DatabaseManager::authenticateUser(const QString &username, const QString &password) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT id, password_hash, salt FROM users WHERE username = ?");
    q.addBindValue(username);
    q.exec();

    if (q.next()) {
        int userId   = q.value(0).toInt();
        QString hash = q.value(1).toString();
        QString salt = q.value(2).toString();

        if (hashPassword(password, salt) == hash) {
            // 更新最后登录时间
            QSqlQuery u(db);
            u.prepare("UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = ?");
            u.addBindValue(userId);
            u.exec();
            return userId;
        }
    }
    return -1;
}

bool DatabaseManager::changePassword(int userId, const QString &oldPassword, const QString &newPassword) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 验证旧密码
    q.prepare("SELECT password_hash, salt FROM users WHERE id = ?");
    q.addBindValue(userId);
    q.exec();
    if (!q.next()) return false;

    QString oldHash = q.value(0).toString();
    QString oldSalt = q.value(1).toString();
    if (hashPassword(oldPassword, oldSalt) != oldHash) return false;

    // 生成新盐值和哈希
    QString newSalt = generateSalt();
    QString newHash = hashPassword(newPassword, newSalt);

    q.prepare("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?");
    q.addBindValue(newHash);
    q.addBindValue(newSalt);
    q.addBindValue(userId);
    return q.exec();
}

QString DatabaseManager::getDisplayName(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT display_name FROM users WHERE id = ?");
    q.addBindValue(userId);
    q.exec();
    if (q.next()) {
        QString dn = q.value(0).toString();
        if (!dn.isEmpty()) return dn;
    }
    // 回退到 username
    q.prepare("SELECT username FROM users WHERE id = ?");
    q.addBindValue(userId);
    q.exec();
    if (q.next()) return q.value(0).toString();
    return {};
}

QString DatabaseManager::getDisplayNameByUid(const QString &uniqueId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT display_name, username FROM users WHERE username = ?");
    q.addBindValue(uniqueId);
    q.exec();
    if (q.next()) {
        QString dn = q.value(0).toString();
        return dn.isEmpty() ? q.value(1).toString() : dn;
    }
    return {};
}

bool DatabaseManager::setDisplayName(int userId, const QString &newDisplayName) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("UPDATE users SET display_name = ? WHERE id = ?");
    q.addBindValue(newDisplayName);
    q.addBindValue(userId);
    return q.exec();
}

QString DatabaseManager::getUniqueId(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT username FROM users WHERE id = ?");
    q.addBindValue(userId);
    q.exec();
    if (q.next()) return q.value(0).toString();
    return {};
}

QDateTime DatabaseManager::getLastUidChangeTime(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT last_uid_change FROM users WHERE id = ?");
    q.addBindValue(userId);
    q.exec();
    if (q.next() && !q.value(0).isNull()) {
        return QDateTime::fromString(q.value(0).toString(), Qt::ISODate);
    }
    return {};  // 返回 invalid QDateTime 表示从未修改过
}

bool DatabaseManager::changeUniqueId(int userId, const QString &newUniqueId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("UPDATE users SET username = ?, last_uid_change = datetime('now') WHERE id = ?");
    q.addBindValue(newUniqueId);
    q.addBindValue(userId);
    return q.exec();
}

// ==================== 房间管理 ====================

int DatabaseManager::createRoom(const QString &name, int creatorId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("INSERT INTO rooms (name, creator_id) VALUES (?, ?)");
    q.addBindValue(name);
    q.addBindValue(creatorId);

    if (q.exec())
        return q.lastInsertId().toInt();

    qWarning() << "[DB] 创建房间失败:" << q.lastError().text();
    return -1;
}

bool DatabaseManager::joinRoom(int roomId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("INSERT OR IGNORE INTO room_members (room_id, user_id) VALUES (?, ?)");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    return q.exec();
}

QJsonArray DatabaseManager::getAllRooms() {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.exec("SELECT id, name, creator_id FROM rooms ORDER BY id");

    QJsonArray arr;
    while (q.next()) {
        QJsonObject room;
        room["roomId"]    = q.value(0).toInt();
        room["roomName"]  = q.value(1).toString();
        room["creatorId"] = q.value(2).toInt();
        arr.append(room);
    }
    return arr;
}

QJsonArray DatabaseManager::getUserJoinedRooms(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT r.id, r.name, r.creator_id FROM rooms r "
              "JOIN room_members rm ON r.id = rm.room_id "
              "WHERE rm.user_id = ? ORDER BY r.id");
    q.addBindValue(userId);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject room;
        room["roomId"]    = q.value(0).toInt();
        room["roomName"]  = q.value(1).toString();
        room["creatorId"] = q.value(2).toInt();
        arr.append(room);
    }
    return arr;
}

bool DatabaseManager::deleteRoom(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    // CASCADE 会自动删除 room_members, messages, files, room_admins, room_settings
    q.prepare("DELETE FROM rooms WHERE id = ?");
    q.addBindValue(roomId);
    return q.exec();
}

QString DatabaseManager::getRoomName(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT name FROM rooms WHERE id = ?");
    q.addBindValue(roomId);
    q.exec();
    if (q.next())
        return q.value(0).toString();
    return {};
}

bool DatabaseManager::renameRoom(int roomId, const QString &newName) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("UPDATE rooms SET name = ? WHERE id = ?");
    q.addBindValue(newName);
    q.addBindValue(roomId);
    return q.exec();
}

int DatabaseManager::getRoomMemberCount(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT COUNT(*) FROM room_members WHERE room_id = ?");
    q.addBindValue(roomId);
    q.exec();
    if (q.next())
        return q.value(0).toInt();
    return 0;
}

bool DatabaseManager::isUserInRoom(int roomId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT 1 FROM room_members WHERE room_id = ? AND user_id = ?");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.exec();
    return q.next();
}

QJsonArray DatabaseManager::getRoomMembers(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT u.id, u.username, u.display_name FROM room_members rm "
              "JOIN users u ON rm.user_id = u.id "
              "WHERE rm.room_id = ? ORDER BY u.username");
    q.addBindValue(roomId);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject user;
        user["userId"]      = q.value(0).toInt();
        user["username"]    = q.value(1).toString();
        QString dn = q.value(2).toString();
        user["displayName"] = dn.isEmpty() ? q.value(1).toString() : dn;
        arr.append(user);
    }
    return arr;
}

bool DatabaseManager::leaveRoom(int roomId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("DELETE FROM room_members WHERE room_id = ? AND user_id = ?");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    return q.exec();
}

int DatabaseManager::getUserIdByName(const QString &username) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT id FROM users WHERE username = ?");
    q.addBindValue(username);
    q.exec();
    if (q.next())
        return q.value(0).toInt();
    return -1;
}

QJsonArray DatabaseManager::searchUsers(const QString &keyword, int excludeUserId, int limit) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 按唯一ID或昵称模糊搜索，排除自己
    q.prepare("SELECT id, username, display_name FROM users "
              "WHERE id != ? AND (username LIKE ? OR display_name LIKE ?) "
              "ORDER BY username LIMIT ?");
    q.addBindValue(excludeUserId);
    QString pattern = "%" + keyword + "%";
    q.addBindValue(pattern);
    q.addBindValue(pattern);
    q.addBindValue(limit);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject user;
        user["userId"]      = q.value(0).toInt();
        user["username"]    = q.value(1).toString();
        QString dn = q.value(2).toString();
        user["displayName"] = dn.isEmpty() ? q.value(1).toString() : dn;
        arr.append(user);
    }
    return arr;
}

// ==================== 聊天室搜索 ====================

QJsonArray DatabaseManager::searchRooms(const QString &keyword, int limit) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 支持按房间名称模糊搜索或按房间ID精确搜索
    bool isId = false;
    int roomId = keyword.toInt(&isId);

    if (isId && roomId > 0) {
        // 按ID精确搜索
        q.prepare("SELECT r.id, r.name, r.creator_id, "
                  "(SELECT COUNT(*) FROM room_members rm WHERE rm.room_id = r.id) AS member_count "
                  "FROM rooms r WHERE r.id = ?");
        q.addBindValue(roomId);
    } else {
        // 按名称模糊搜索
        q.prepare("SELECT r.id, r.name, r.creator_id, "
                  "(SELECT COUNT(*) FROM room_members rm WHERE rm.room_id = r.id) AS member_count "
                  "FROM rooms r WHERE r.name LIKE ? ORDER BY r.id LIMIT ?");
        QString pattern = "%" + keyword + "%";
        q.addBindValue(pattern);
        q.addBindValue(limit);
    }
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject room;
        room["roomId"]      = q.value(0).toInt();
        room["roomName"]    = q.value(1).toString();
        room["creatorId"]   = q.value(2).toInt();
        room["memberCount"] = q.value(3).toInt();
        arr.append(room);
    }
    return arr;
}

// ==================== 聊天室头像 ====================

QByteArray DatabaseManager::getRoomAvatar(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT avatar_data FROM room_avatars WHERE room_id = ?");
    q.addBindValue(roomId);
    q.exec();
    if (q.next())
        return q.value(0).toByteArray();
    return {};
}

bool DatabaseManager::setRoomAvatar(int roomId, const QByteArray &avatarData) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("INSERT OR REPLACE INTO room_avatars (room_id, avatar_data, updated_at) "
              "VALUES (?, ?, CURRENT_TIMESTAMP)");
    q.addBindValue(roomId);
    q.addBindValue(avatarData);
    return q.exec();
}

// ==================== 消息管理 ====================

int DatabaseManager::saveMessage(int roomId, int userId, const QString &content,
                                  const QString &contentType,
                                  const QString &fileName, qint64 fileSize, int fileId,
                                  const QString &thumbnail) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("INSERT INTO messages (room_id, user_id, content, content_type, file_name, file_size, file_id, thumbnail)"
              " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.addBindValue(content);
    q.addBindValue(contentType);
    q.addBindValue(fileName);
    q.addBindValue(fileSize);
    q.addBindValue(fileId);
    q.addBindValue(thumbnail);

    if (q.exec())
        return q.lastInsertId().toInt();

    qWarning() << "[DB] 保存消息失败:" << q.lastError().text();
    return -1;
}

QJsonArray DatabaseManager::getMessageHistory(int roomId, int count, qint64 beforeTimestamp) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 用子查询取最新N条（DESC），再按时间正序排列（ASC）
    QString sql = "SELECT * FROM ("
                  "SELECT m.id, m.content, m.content_type, m.file_name, m.file_size, m.file_id,"
                  "       m.recalled, m.created_at, u.username, u.display_name, m.thumbnail"
                  " FROM messages m JOIN users u ON m.user_id = u.id"
                  " WHERE m.room_id = ?";

    if (beforeTimestamp > 0)
        sql += " AND m.created_at < datetime(? / 1000, 'unixepoch')";

    sql += " ORDER BY m.created_at DESC LIMIT ?"
           ") ORDER BY created_at ASC";

    q.prepare(sql);
    q.addBindValue(roomId);
    if (beforeTimestamp > 0)
        q.addBindValue(beforeTimestamp);
    q.addBindValue(count);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject msg;
        msg["id"]          = q.value(0).toInt();
        msg["content"]     = q.value(1).toString();
        msg["contentType"] = q.value(2).toString();
        msg["fileName"]    = q.value(3).toString();
        msg["fileSize"]    = static_cast<double>(q.value(4).toLongLong());
        msg["fileId"]      = q.value(5).toInt();
        msg["recalled"]    = q.value(6).toInt() != 0;

        // SQLite CURRENT_TIMESTAMP 存储 UTC 时间，需明确指定 TimeSpec
        QDateTime dt = q.value(7).toDateTime();
        dt.setTimeSpec(Qt::UTC);
        msg["timestamp"]   = dt.toMSecsSinceEpoch();

        msg["sender"]      = q.value(8).toString();  // uniqueId (username列)
        QString dn = q.value(9).toString();
        msg["senderName"]  = dn.isEmpty() ? msg["sender"].toString() : dn;
        msg["roomId"]      = roomId;

        // 缩略图
        QString thumb = q.value(10).toString();
        if (!thumb.isEmpty())
            msg["thumbnail"] = thumb;

        arr.append(msg);
    }
    return arr;
}

bool DatabaseManager::recallMessage(int messageId, int userId, int timeLimitSec) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 检查消息存在性
    q.prepare("SELECT user_id, created_at, room_id FROM messages WHERE id = ? AND recalled = 0");
    q.addBindValue(messageId);
    q.exec();

    if (!q.next()) return false;

    int ownerId = q.value(0).toInt();
    QDateTime createdAt = q.value(1).toDateTime();
    createdAt.setTimeSpec(Qt::UTC);  // SQLite CURRENT_TIMESTAMP 存储 UTC

    // 所有用户（包括管理员）只能撤回自己的消息，且有时间限制
    if (ownerId != userId) return false;
    if (createdAt.secsTo(QDateTime::currentDateTimeUtc()) > timeLimitSec) return false;

    // 执行撤回
    q.prepare("UPDATE messages SET recalled = 1, content = '此消息已被撤回' WHERE id = ?");
    q.addBindValue(messageId);
    return q.exec();
}

QPair<int, QString> DatabaseManager::getFileInfoForMessage(int messageId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT m.file_id, f.file_path FROM messages m "
              "LEFT JOIN files f ON m.file_id = f.id "
              "WHERE m.id = ? AND m.file_id > 0");
    q.addBindValue(messageId);
    if (q.exec() && q.next())
        return {q.value(0).toInt(), q.value(1).toString()};
    return {0, {}};
}

// ==================== 文件管理 ====================

int DatabaseManager::saveFile(int roomId, int userId, const QString &fileName,
                               const QString &filePath, qint64 fileSize) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("INSERT INTO files (room_id, user_id, file_name, file_path, file_size)"
              " VALUES (?, ?, ?, ?, ?)");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.addBindValue(fileName);
    q.addBindValue(filePath);
    q.addBindValue(fileSize);

    if (q.exec())
        return q.lastInsertId().toInt();

    qWarning() << "[DB] 保存文件记录失败:" << q.lastError().text();
    return -1;
}

QString DatabaseManager::getFilePath(int fileId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT file_path FROM files WHERE id = ?");
    q.addBindValue(fileId);
    q.exec();

    if (q.next())
        return q.value(0).toString();
    return {};
}

QString DatabaseManager::getFileName(int fileId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT file_name FROM files WHERE id = ?");
    q.addBindValue(fileId);
    q.exec();

    if (q.next())
        return q.value(0).toString();
    return {};
}

// ==================== 管理员管理 ====================

bool DatabaseManager::isRoomAdmin(int roomId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT 1 FROM room_admins WHERE room_id = ? AND user_id = ?");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.exec();
    return q.next();
}

bool DatabaseManager::isRoomCreator(int roomId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT 1 FROM rooms WHERE id = ? AND creator_id = ?");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.exec();
    return q.next();
}

bool DatabaseManager::setRoomAdmin(int roomId, int userId, bool isAdmin) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    if (isAdmin) {
        q.prepare("INSERT OR IGNORE INTO room_admins (room_id, user_id) VALUES (?, ?)");
    } else {
        q.prepare("DELETE FROM room_admins WHERE room_id = ? AND user_id = ?");
    }
    q.addBindValue(roomId);
    q.addBindValue(userId);
    return q.exec();
}

QList<int> DatabaseManager::getRoomAdmins(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 只查询 room_admins 表中的显式管理员
    q.prepare("SELECT user_id FROM room_admins WHERE room_id = ?");
    q.addBindValue(roomId);
    q.exec();

    QList<int> admins;
    while (q.next()) {
        admins.append(q.value(0).toInt());
    }
    return admins;
}

bool DatabaseManager::hasAnyAdmin(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 只检查 room_admins 表
    q.prepare("SELECT 1 FROM room_admins WHERE room_id = ? LIMIT 1");
    q.addBindValue(roomId);
    q.exec();
    return q.next();
}

// ==================== 管理员操作 - 删除消息 ====================

bool DatabaseManager::deleteMessages(int roomId, const QList<int> &messageIds) {
    if (messageIds.isEmpty()) return true;

    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    QStringList placeholders;
    for (int i = 0; i < messageIds.size(); ++i)
        placeholders.append("?");

    QString sql = QString("DELETE FROM messages WHERE room_id = ? AND id IN (%1)")
                      .arg(placeholders.join(","));
    q.prepare(sql);
    q.addBindValue(roomId);
    for (int id : messageIds)
        q.addBindValue(id);

    return q.exec();
}

int DatabaseManager::deleteAllMessages(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("DELETE FROM messages WHERE room_id = ?");
    q.addBindValue(roomId);
    if (q.exec())
        return q.numRowsAffected();
    return -1;
}

int DatabaseManager::deleteMessagesBefore(int roomId, const QDateTime &before) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("DELETE FROM messages WHERE room_id = ? AND created_at < ?");
    q.addBindValue(roomId);
    q.addBindValue(before.toUTC().toString("yyyy-MM-dd HH:mm:ss"));
    if (q.exec())
        return q.numRowsAffected();
    return -1;
}

int DatabaseManager::deleteMessagesAfter(int roomId, const QDateTime &after) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("DELETE FROM messages WHERE room_id = ? AND created_at > ?");
    q.addBindValue(roomId);
    q.addBindValue(after.toUTC().toString("yyyy-MM-dd HH:mm:ss"));
    if (q.exec())
        return q.numRowsAffected();
    return -1;
}

// ==================== 文件清理辅助方法 ====================

QList<QPair<int, QString>> DatabaseManager::getFileInfoForMessages(int roomId, const QList<int> &messageIds) {
    QList<QPair<int, QString>> result;
    if (messageIds.isEmpty()) return result;

    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    QStringList placeholders;
    for (int i = 0; i < messageIds.size(); ++i)
        placeholders.append("?");

    QString sql = QString("SELECT DISTINCT m.file_id, f.file_path FROM messages m "
                          "JOIN files f ON m.file_id = f.id "
                          "WHERE m.room_id = ? AND m.file_id > 0 AND m.id IN (%1)")
                      .arg(placeholders.join(","));
    q.prepare(sql);
    q.addBindValue(roomId);
    for (int id : messageIds)
        q.addBindValue(id);

    if (q.exec()) {
        while (q.next())
            result.append({q.value(0).toInt(), q.value(1).toString()});
    }
    return result;
}

QList<QPair<int, QString>> DatabaseManager::getAllFileInfoForRoom(int roomId) {
    QList<QPair<int, QString>> result;
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT DISTINCT m.file_id, f.file_path FROM messages m "
              "JOIN files f ON m.file_id = f.id "
              "WHERE m.room_id = ? AND m.file_id > 0");
    q.addBindValue(roomId);

    if (q.exec()) {
        while (q.next())
            result.append({q.value(0).toInt(), q.value(1).toString()});
    }
    return result;
}

QList<QPair<int, QString>> DatabaseManager::getFileInfoBeforeTime(int roomId, const QDateTime &before) {
    QList<QPair<int, QString>> result;
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT DISTINCT m.file_id, f.file_path FROM messages m "
              "JOIN files f ON m.file_id = f.id "
              "WHERE m.room_id = ? AND m.file_id > 0 AND m.created_at < ?");
    q.addBindValue(roomId);
    q.addBindValue(before.toUTC().toString("yyyy-MM-dd HH:mm:ss"));

    if (q.exec()) {
        while (q.next())
            result.append({q.value(0).toInt(), q.value(1).toString()});
    }
    return result;
}

QList<QPair<int, QString>> DatabaseManager::getFileInfoAfterTime(int roomId, const QDateTime &after) {
    QList<QPair<int, QString>> result;
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT DISTINCT m.file_id, f.file_path FROM messages m "
              "JOIN files f ON m.file_id = f.id "
              "WHERE m.room_id = ? AND m.file_id > 0 AND m.created_at > ?");
    q.addBindValue(roomId);
    q.addBindValue(after.toUTC().toString("yyyy-MM-dd HH:mm:ss"));

    if (q.exec()) {
        while (q.next())
            result.append({q.value(0).toInt(), q.value(1).toString()});
    }
    return result;
}

bool DatabaseManager::deleteFileRecords(const QList<int> &fileIds) {
    if (fileIds.isEmpty()) return true;

    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    QStringList placeholders;
    for (int i = 0; i < fileIds.size(); ++i)
        placeholders.append("?");

    QString sql = QString("DELETE FROM files WHERE id IN (%1)").arg(placeholders.join(","));
    q.prepare(sql);
    for (int id : fileIds)
        q.addBindValue(id);

    return q.exec();
}

// ==================== 房间设置 ====================

qint64 DatabaseManager::getRoomMaxFileSize(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT max_file_size FROM room_settings WHERE room_id = ?");
    q.addBindValue(roomId);
    q.exec();
    if (q.next())
        return q.value(0).toLongLong();
    return 4LL * 1024 * 1024 * 1024; // 默认 4GB
}

bool DatabaseManager::setRoomMaxFileSize(int roomId, qint64 maxSize) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("INSERT OR REPLACE INTO room_settings (room_id, max_file_size) VALUES (?, ?)");
    q.addBindValue(roomId);
    q.addBindValue(maxSize);
    return q.exec();
}

// ==================== 用户头像 ====================

QByteArray DatabaseManager::getUserAvatar(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT avatar_data FROM user_avatars WHERE user_id = ?");
    q.addBindValue(userId);
    q.exec();
    if (q.next())
        return q.value(0).toByteArray();
    return {};
}

bool DatabaseManager::setUserAvatar(int userId, const QByteArray &avatarData) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("INSERT OR REPLACE INTO user_avatars (user_id, avatar_data, updated_at) "
              "VALUES (?, ?, CURRENT_TIMESTAMP)");
    q.addBindValue(userId);
    q.addBindValue(avatarData);
    return q.exec();
}

QByteArray DatabaseManager::getUserAvatarByName(const QString &username) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT a.avatar_data FROM user_avatars a "
              "JOIN users u ON a.user_id = u.id WHERE u.username = ?");
    q.addBindValue(username);
    q.exec();
    if (q.next())
        return q.value(0).toByteArray();
    return {};
}

bool DatabaseManager::setRoomPassword(int roomId, const QString &password) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    if (password.isEmpty()) {
        q.prepare("UPDATE rooms SET password = NULL WHERE id = ?");
        q.addBindValue(roomId);
    } else {
        q.prepare("UPDATE rooms SET password = ? WHERE id = ?");
        q.addBindValue(password);
        q.addBindValue(roomId);
    }
    return q.exec();
}

QString DatabaseManager::getRoomPassword(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT password FROM rooms WHERE id = ?");
    q.addBindValue(roomId);
    q.exec();
    if (q.next())
        return q.value(0).toString();
    return {};
}

bool DatabaseManager::roomHasPassword(int roomId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT password FROM rooms WHERE id = ? AND password IS NOT NULL AND password != ''");
    q.addBindValue(roomId);
    q.exec();
    return q.next();
}

// ==================== 好友系统 ====================

bool DatabaseManager::sendFriendRequest(int fromUserId, int toUserId) {
    if (fromUserId == toUserId) return false;
    if (areFriends(fromUserId, toUserId)) return false;

    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 检查是否已有待处理的请求
    q.prepare("SELECT id FROM friend_requests WHERE from_user_id = ? AND to_user_id = ? AND status = 'pending'");
    q.addBindValue(fromUserId);
    q.addBindValue(toUserId);
    q.exec();
    if (q.next()) return false; // 已有待处理请求

    // 检查对方是否已经向自己发过请求（如果有，直接接受）
    q.prepare("SELECT id FROM friend_requests WHERE from_user_id = ? AND to_user_id = ? AND status = 'pending'");
    q.addBindValue(toUserId);
    q.addBindValue(fromUserId);
    q.exec();
    if (q.next()) {
        // 对方已向自己发请求，直接接受
        int reqId = q.value(0).toInt();
        return acceptFriendRequest(reqId, fromUserId);
    }

    q.prepare("INSERT INTO friend_requests (from_user_id, to_user_id, status) VALUES (?, ?, 'pending')");
    q.addBindValue(fromUserId);
    q.addBindValue(toUserId);
    return q.exec();
}

bool DatabaseManager::acceptFriendRequest(int requestId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 获取请求信息并验证
    q.prepare("SELECT from_user_id, to_user_id FROM friend_requests WHERE id = ? AND status = 'pending'");
    q.addBindValue(requestId);
    q.exec();
    if (!q.next()) return false;

    int fromId = q.value(0).toInt();
    int toId   = q.value(1).toInt();
    if (toId != userId) return false; // 只有被请求方能接受

    // 更新请求状态
    q.prepare("UPDATE friend_requests SET status = 'accepted' WHERE id = ?");
    q.addBindValue(requestId);
    if (!q.exec()) return false;

    // 创建好友关系 (保证 user_id1 < user_id2)
    int id1 = qMin(fromId, toId);
    int id2 = qMax(fromId, toId);
    q.prepare("INSERT OR IGNORE INTO friendships (user_id1, user_id2) VALUES (?, ?)");
    q.addBindValue(id1);
    q.addBindValue(id2);
    return q.exec();
}

bool DatabaseManager::rejectFriendRequest(int requestId, int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT to_user_id FROM friend_requests WHERE id = ? AND status = 'pending'");
    q.addBindValue(requestId);
    q.exec();
    if (!q.next()) return false;
    if (q.value(0).toInt() != userId) return false;

    q.prepare("UPDATE friend_requests SET status = 'rejected' WHERE id = ?");
    q.addBindValue(requestId);
    return q.exec();
}

QJsonArray DatabaseManager::getPendingFriendRequests(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT fr.id, fr.from_user_id, u.username, u.display_name, fr.created_at "
              "FROM friend_requests fr "
              "JOIN users u ON fr.from_user_id = u.id "
              "WHERE fr.to_user_id = ? AND fr.status = 'pending' "
              "ORDER BY fr.created_at DESC");
    q.addBindValue(userId);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject req;
        req["requestId"]   = q.value(0).toInt();
        req["fromUserId"]  = q.value(1).toInt();
        req["fromUsername"] = q.value(2).toString();
        QString dn = q.value(3).toString();
        req["fromDisplayName"] = dn.isEmpty() ? q.value(2).toString() : dn;
        QDateTime dt = q.value(4).toDateTime();
        dt.setTimeSpec(Qt::UTC);
        req["timestamp"] = dt.toMSecsSinceEpoch();
        arr.append(req);
    }
    return arr;
}

QJsonArray DatabaseManager::getFriendList(int userId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("SELECT f.id, "
              "  CASE WHEN f.user_id1 = ? THEN f.user_id2 ELSE f.user_id1 END AS friend_id, "
              "  u.username, u.display_name "
              "FROM friendships f "
              "JOIN users u ON u.id = CASE WHEN f.user_id1 = ? THEN f.user_id2 ELSE f.user_id1 END "
              "WHERE f.user_id1 = ? OR f.user_id2 = ? "
              "ORDER BY u.display_name");
    q.addBindValue(userId);
    q.addBindValue(userId);
    q.addBindValue(userId);
    q.addBindValue(userId);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject fr;
        fr["friendshipId"] = q.value(0).toInt();
        fr["friendId"]     = q.value(1).toInt();
        fr["username"]     = q.value(2).toString();
        QString dn = q.value(3).toString();
        fr["displayName"]  = dn.isEmpty() ? q.value(2).toString() : dn;
        arr.append(fr);
    }
    return arr;
}

bool DatabaseManager::areFriends(int userId1, int userId2) {
    int id1 = qMin(userId1, userId2);
    int id2 = qMax(userId1, userId2);
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT 1 FROM friendships WHERE user_id1 = ? AND user_id2 = ?");
    q.addBindValue(id1);
    q.addBindValue(id2);
    q.exec();
    return q.next();
}

bool DatabaseManager::removeFriend(int userId1, int userId2) {
    int id1 = qMin(userId1, userId2);
    int id2 = qMax(userId1, userId2);
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("DELETE FROM friendships WHERE user_id1 = ? AND user_id2 = ?");
    q.addBindValue(id1);
    q.addBindValue(id2);
    return q.exec();
}

int DatabaseManager::getFriendshipId(int userId1, int userId2) {
    int id1 = qMin(userId1, userId2);
    int id2 = qMax(userId1, userId2);
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("SELECT id FROM friendships WHERE user_id1 = ? AND user_id2 = ?");
    q.addBindValue(id1);
    q.addBindValue(id2);
    q.exec();
    if (q.next()) return q.value(0).toInt();
    return -1;
}

int DatabaseManager::saveFriendMessage(int friendshipId, int senderId, const QString &content,
                                       const QString &contentType,
                                       const QString &fileName, qint64 fileSize, int fileId,
                                       const QString &thumbnail) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("INSERT INTO friend_messages (friendship_id, sender_id, content, content_type, file_name, file_size, file_id, thumbnail)"
              " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
    q.addBindValue(friendshipId);
    q.addBindValue(senderId);
    q.addBindValue(content);
    q.addBindValue(contentType);
    q.addBindValue(fileName);
    q.addBindValue(fileSize);
    q.addBindValue(fileId);
    q.addBindValue(thumbnail);
    if (q.exec()) return q.lastInsertId().toInt();
    return -1;
}

QJsonArray DatabaseManager::getFriendMessageHistory(int friendshipId, int count, qint64 beforeTimestamp) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    QString sql = "SELECT * FROM ("
                  "SELECT m.id, m.content, m.content_type, m.file_name, m.file_size, m.file_id,"
                  "       m.recalled, m.created_at, u.username, u.display_name, m.thumbnail"
                  " FROM friend_messages m JOIN users u ON m.sender_id = u.id"
                  " WHERE m.friendship_id = ?";
    if (beforeTimestamp > 0)
        sql += " AND m.created_at < datetime(? / 1000, 'unixepoch')";
    sql += " ORDER BY m.created_at DESC LIMIT ?"
           ") ORDER BY created_at ASC";

    q.prepare(sql);
    q.addBindValue(friendshipId);
    if (beforeTimestamp > 0) q.addBindValue(beforeTimestamp);
    q.addBindValue(count);
    q.exec();

    QJsonArray arr;
    while (q.next()) {
        QJsonObject msg;
        msg["id"]          = q.value(0).toInt();
        msg["content"]     = q.value(1).toString();
        msg["contentType"] = q.value(2).toString();
        msg["fileName"]    = q.value(3).toString();
        msg["fileSize"]    = static_cast<double>(q.value(4).toLongLong());
        msg["fileId"]      = q.value(5).toInt();
        msg["recalled"]    = q.value(6).toInt() != 0;
        QDateTime dt = q.value(7).toDateTime();
        dt.setTimeSpec(Qt::UTC);
        msg["timestamp"]   = dt.toMSecsSinceEpoch();
        msg["sender"]      = q.value(8).toString();
        QString dn = q.value(9).toString();
        msg["senderName"]  = dn.isEmpty() ? msg["sender"].toString() : dn;
        msg["friendshipId"] = friendshipId;
        QString thumb = q.value(10).toString();
        if (!thumb.isEmpty()) msg["thumbnail"] = thumb;
        arr.append(msg);
    }
    return arr;
}

int DatabaseManager::saveFriendFile(int friendshipId, int userId, const QString &fileName,
                                    const QString &filePath, qint64 fileSize) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);
    q.prepare("INSERT INTO friend_files (friendship_id, user_id, file_name, file_path, file_size)"
              " VALUES (?, ?, ?, ?, ?)");
    q.addBindValue(friendshipId);
    q.addBindValue(userId);
    q.addBindValue(fileName);
    q.addBindValue(filePath);
    q.addBindValue(fileSize);
    if (q.exec()) return q.lastInsertId().toInt();
    return -1;
}