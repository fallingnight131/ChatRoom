# Qt聊天室 (ChatRoom)

基于 C++17/Qt 的功能完整聊天室系统，包含独立的服务器和客户端程序。

## 功能特性

- 🔐 用户注册/登录（MySQL + SHA-256 密码加密）
- 💬 群组聊天室创建与加入
- 📎 文件传输（图片/文档，最大 10MB）
- 📜 消息历史记录
- 😊 150+ 表情符号支持
- ↩️ 消息撤回（2分钟内）
- 🔄 心跳保活 & 断线自动重连
- 🎨 深色/浅色主题切换 (Ctrl+T)
- 🔔 系统托盘通知
- 📌 窗口贴边隐藏

## 快速开始

### 1. 准备数据库

```bash
mysql -u root -p < database_setup.sql
```

### 2. 编译项目

```bash
mkdir build && cd build
qmake ../ChatRoom.pro
make -j$(nproc)
```

### 3. 启动服务器

```bash
# 配置数据库连接
export CHATROOM_DB_HOST=localhost
export CHATROOM_DB_USER=root
export CHATROOM_DB_PASS=your_password

# 启动（默认端口 9527）
./Server/ChatServer
```

### 4. 启动客户端

```bash
./Client/ChatClient
```

## 依赖

- Qt 5.15+ / Qt 6.x（Core, Network, Sql, Widgets）
- MySQL 5.7+ / MariaDB 10.3+
- QMYSQL 驱动
- C++17 编译器

## 项目结构

```
ChatRoom/
├── Common/       # 共享协议与消息定义
├── Server/       # 服务器程序
├── Client/       # 客户端程序
├── database_setup.sql
└── DESIGN.md     # 详细设计文档
```

## 许可

MIT License
