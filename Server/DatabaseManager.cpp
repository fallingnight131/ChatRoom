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
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,"
           "  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"
           ")");

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

    // 创建默认大厅
    q.prepare("SELECT id FROM rooms WHERE id = 1");
    q.exec();
    if (!q.next()) {
        // 确保有一个系统用户
        q.prepare("INSERT OR IGNORE INTO users (id, username, password_hash, salt) VALUES (1, 'system', '', '')");
        q.exec();
        q.prepare("INSERT OR IGNORE INTO rooms (id, name, creator_id) VALUES (1, '大厅', 1)");
        q.exec();
    }

    m_initialized = true;
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

int DatabaseManager::registerUser(const QString &username, const QString &password) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 检查重名
    q.prepare("SELECT id FROM users WHERE username = ?");
    q.addBindValue(username);
    q.exec();
    if (q.next()) return -1;

    QString salt = generateSalt();
    QString hash = hashPassword(password, salt);

    q.prepare("INSERT INTO users (username, password_hash, salt) VALUES (?, ?, ?)");
    q.addBindValue(username);
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

// ==================== 消息管理 ====================

int DatabaseManager::saveMessage(int roomId, int userId, const QString &content,
                                  const QString &contentType,
                                  const QString &fileName, qint64 fileSize, int fileId) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    q.prepare("INSERT INTO messages (room_id, user_id, content, content_type, file_name, file_size, file_id)"
              " VALUES (?, ?, ?, ?, ?, ?, ?)");
    q.addBindValue(roomId);
    q.addBindValue(userId);
    q.addBindValue(content);
    q.addBindValue(contentType);
    q.addBindValue(fileName);
    q.addBindValue(fileSize);
    q.addBindValue(fileId);

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
                  "       m.recalled, m.created_at, u.username"
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

        msg["sender"]      = q.value(8).toString();
        msg["roomId"]      = roomId;
        arr.append(msg);
    }
    return arr;
}

bool DatabaseManager::recallMessage(int messageId, int userId, int timeLimitSec) {
    QSqlDatabase db = getConnection();
    QSqlQuery q(db);

    // 检查消息存在性、所有权和时间
    q.prepare("SELECT user_id, created_at FROM messages WHERE id = ? AND recalled = 0");
    q.addBindValue(messageId);
    q.exec();

    if (!q.next()) return false;

    int ownerId = q.value(0).toInt();
    QDateTime createdAt = q.value(1).toDateTime();

    if (ownerId != userId) return false;
    if (createdAt.secsTo(QDateTime::currentDateTime()) > timeLimitSec) return false;

    // 执行撤回
    q.prepare("UPDATE messages SET recalled = 1, content = '此消息已被撤回' WHERE id = ?");
    q.addBindValue(messageId);
    return q.exec();
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

// ==================== 管理员管理 ====================

bool DatabaseManager::isRoomAdmin(int roomId, int userId) {
    // 房间创建者自动拥有管理员权限
    if (isRoomCreator(roomId, userId))
        return true;

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

    // 包含创建者和显式管理员
    q.prepare("SELECT creator_id FROM rooms WHERE id = ?");
    q.addBindValue(roomId);
    q.exec();

    QList<int> admins;
    if (q.next())
        admins.append(q.value(0).toInt());

    q.prepare("SELECT user_id FROM room_admins WHERE room_id = ?");
    q.addBindValue(roomId);
    q.exec();
    while (q.next()) {
        int uid = q.value(0).toInt();
        if (!admins.contains(uid))
            admins.append(uid);
    }
    return admins;
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
