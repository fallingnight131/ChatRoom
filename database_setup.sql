-- ==========================================
-- Qt聊天室 数据库初始化脚本
-- MySQL 5.7+ / MariaDB 10.3+
-- ==========================================

CREATE DATABASE IF NOT EXISTS chatroom
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE chatroom;

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  UNIQUE NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    salt        VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    last_login  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天室表
CREATE TABLE IF NOT EXISTS rooms (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    creator_id  INT          NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天室成员关系表
CREATE TABLE IF NOT EXISTS room_members (
    room_id     INT NOT NULL,
    user_id     INT NOT NULL,
    joined_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (room_id, user_id),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    room_id      INT          NOT NULL,
    user_id      INT          NOT NULL,
    content      TEXT,
    content_type VARCHAR(20)  DEFAULT 'text',
    file_name    VARCHAR(256) DEFAULT '',
    file_size    BIGINT       DEFAULT 0,
    file_id      INT          DEFAULT 0,
    recalled     BOOLEAN      DEFAULT FALSE,
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_room_time (room_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文件存储记录表
CREATE TABLE IF NOT EXISTS files (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    room_id     INT          NOT NULL,
    user_id     INT          NOT NULL,
    file_name   VARCHAR(256) NOT NULL,
    file_path   VARCHAR(512) NOT NULL,
    file_size   BIGINT       DEFAULT 0,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入系统用户和默认大厅
INSERT IGNORE INTO users (id, username, password_hash, salt)
    VALUES (1, 'system', '', '');

INSERT IGNORE INTO rooms (id, name, creator_id)
    VALUES (1, '大厅', 1);

-- 查看创建结果
SELECT '=== 数据库初始化完成 ===' AS status;
SHOW TABLES;
