#include "DatabaseManager.h"

#include <QSqlQuery>
#include <QSqlError>
#include <QCryptographicHash>
#include <QUuid>
#include <QThread>
#include <QDebug>
#include <QDateTime>
#include <QJsonObject>

DatabaseManager::DatabaseManager(QObject *parent)
    : QObject(parent)
{
    // 可通过环境变量配置数据库
    if (qEnvironmentVariableIsSet("CHATROOM_DB_HOST"))
        m_host = qEnvironmentVariable("CHATROOM_DB_HOST");
    if (qEnvironmentVariableIsSet("CHATROOM_DB_PORT"))
        m_port = qEnvironmentVariable("CHATROOM_DB_PORT").toInt();
    if (qEnvironmentVariableIsSet("CHATROOM_DB_NAME"))
        m_dbName = qEnvironmentVariable("CHATROOM_DB_NAME");
    if (qEnvironmentVariableIsSet("CHATROOM_DB_USER"))
        m_user = qEnvironmentVariable("CHATROOM_DB_USER");
    if (qEnvironmentVariableIsSet("CHATROOM_DB_PASS"))
        m_password = qEnvironmentVariable("CHATROOM_DB_PASS");
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

    QSqlDatabase db = QSqlDatabase::addDatabase("QMYSQL", connName);
    db.setHostName(m_host);
    db.setPort(m_port);
    db.setDatabaseName(m_dbName);
    db.setUserName(m_user);
    db.setPassword(m_password);

    if (!db.open()) {
        qCritical() << "[DB] 连接失败:" << db.lastError().text();
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
           "  id INT AUTO_INCREMENT PRIMARY KEY,"
           "  username VARCHAR(50) UNIQUE NOT NULL,"
           "  password_hash VARCHAR(128) NOT NULL,"
           "  salt VARCHAR(64) NOT NULL,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
           ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    if (q.lastError().isValid())
        qWarning() << "[DB] 创建 users 表错误:" << q.lastError().text();

    // 创建房间表
    q.exec("CREATE TABLE IF NOT EXISTS rooms ("
           "  id INT AUTO_INCREMENT PRIMARY KEY,"
           "  name VARCHAR(100) NOT NULL,"
           "  creator_id INT NOT NULL,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (creator_id) REFERENCES users(id)"
           ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    // 房间成员表
    q.exec("CREATE TABLE IF NOT EXISTS room_members ("
           "  room_id INT NOT NULL,"
           "  user_id INT NOT NULL,"
           "  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  PRIMARY KEY (room_id, user_id),"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id),"
           "  FOREIGN KEY (user_id) REFERENCES users(id)"
           ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    // 消息表
    q.exec("CREATE TABLE IF NOT EXISTS messages ("
           "  id INT AUTO_INCREMENT PRIMARY KEY,"
           "  room_id INT NOT NULL,"
           "  user_id INT NOT NULL,"
           "  content TEXT,"
           "  content_type VARCHAR(20) DEFAULT 'text',"
           "  file_name VARCHAR(256) DEFAULT '',"
           "  file_size BIGINT DEFAULT 0,"
           "  file_id INT DEFAULT 0,"
           "  recalled BOOLEAN DEFAULT FALSE,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
           "  FOREIGN KEY (room_id) REFERENCES rooms(id),"
           "  FOREIGN KEY (user_id) REFERENCES users(id)"
           ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    // 文件表
    q.exec("CREATE TABLE IF NOT EXISTS files ("
           "  id INT AUTO_INCREMENT PRIMARY KEY,"
           "  room_id INT NOT NULL,"
           "  user_id INT NOT NULL,"
           "  file_name VARCHAR(256) NOT NULL,"
           "  file_path VARCHAR(512) NOT NULL,"
           "  file_size BIGINT DEFAULT 0,"
           "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
           ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    // 创建默认大厅
    q.prepare("SELECT id FROM rooms WHERE id = 1");
    q.exec();
    if (!q.next()) {
        // 确保有一个系统用户
        q.prepare("INSERT IGNORE INTO users (id, username, password_hash, salt) VALUES (1, 'system', '', '')");
        q.exec();
        q.prepare("INSERT INTO rooms (id, name, creator_id) VALUES (1, '大厅', 1)");
        q.exec();
    }

    m_initialized = true;
    qInfo() << "[DB] 数据库初始化完成";
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

    q.prepare("INSERT IGNORE INTO room_members (room_id, user_id) VALUES (?, ?)");
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

    QString sql = "SELECT m.id, m.content, m.content_type, m.file_name, m.file_size, m.file_id,"
                  "       m.recalled, m.created_at, u.username"
                  " FROM messages m JOIN users u ON m.user_id = u.id"
                  " WHERE m.room_id = ?";

    if (beforeTimestamp > 0)
        sql += " AND m.created_at < FROM_UNIXTIME(? / 1000)";

    sql += " ORDER BY m.created_at DESC LIMIT ?";

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
        msg["recalled"]    = q.value(6).toBool();
        msg["timestamp"]   = q.value(7).toDateTime().toMSecsSinceEpoch();
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
    q.prepare("SELECT user_id, created_at FROM messages WHERE id = ? AND recalled = FALSE");
    q.addBindValue(messageId);
    q.exec();

    if (!q.next()) return false;

    int ownerId = q.value(0).toInt();
    QDateTime createdAt = q.value(1).toDateTime();

    if (ownerId != userId) return false;
    if (createdAt.secsTo(QDateTime::currentDateTime()) > timeLimitSec) return false;

    // 执行撤回
    q.prepare("UPDATE messages SET recalled = TRUE, content = '此消息已被撤回' WHERE id = ?");
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
