# Qt聊天室 — 详细设计文档

## 1. 项目概述

基于 C++17/Qt 框架开发的完整聊天室系统，包含独立的**服务器程序**和**客户端程序**，通过自定义 TCP 协议进行通信。

### 1.1 技术栈

| 组件       | 技术            |
| ---------- | --------------- |
| 语言       | C++17           |
| GUI 框架   | Qt 5.15+ / Qt 6 |
| 数据库     | MySQL 5.7+      |
| 构建系统   | qmake           |
| 网络       | TCP / SSL(可选)  |
| 数据格式   | JSON            |

---

## 2. 架构设计

### 2.1 整体架构

```
┌───────────────────────────────────────────────────────────┐
│                    ChatRoom 项目                          │
├──────────┬──────────────────────┬─────────────────────────┤
│  Common  │       Server         │         Client          │
│          │                      │                         │
│ Protocol │  ChatServer          │  NetworkManager (单例)   │
│ Message  │  ClientSession       │  LoginDialog            │
│          │  DatabaseManager     │  ChatWindow             │
│          │  RoomManager         │  MessageModel           │
│          │                      │  MessageDelegate        │
│          │                      │  EmojiPicker            │
│          │                      │  ThemeManager (单例)     │
│          │                      │  TrayManager            │
└──────────┴──────────────────────┴─────────────────────────┘
```

### 2.2 设计模式

| 模式       | 应用位置                              | 说明                         |
| ---------- | ------------------------------------- | ---------------------------- |
| **MVC**    | MessageModel / MessageDelegate / ChatWindow | 消息数据与视图分离          |
| **单例**   | NetworkManager / ThemeManager         | 全局唯一的网络和主题管理     |
| **观察者** | Qt 信号/槽机制                        | 网络事件通知 UI 更新         |
| **工厂**   | Message::createXxxMessage()           | 不同类型消息的统一创建       |

### 2.3 线程模型

```
服务器线程模型:
┌─────────────────────────────────────────────┐
│ 主线程 (事件循环)                            │
│ ├── ChatServer (监听新连接)                  │
│ ├── RoomManager (房间管理，消息路由)          │
│ └── 接收 ClientSession 的跨线程信号          │
├─────────────────────────────────────────────┤
│ 工作线程 1 → ClientSession + QTcpSocket      │
│ 工作线程 2 → ClientSession + QTcpSocket      │
│ 工作线程 N → ClientSession + QTcpSocket      │
└─────────────────────────────────────────────┘

客户端线程模型:
┌─────────────────────────────────────────────┐
│ 主线程 (GUI 事件循环)                        │
│ ├── ChatWindow (UI)                          │
│ ├── NetworkManager (网络 I/O, 事件驱动)      │
│ └── 所有 UI 组件                             │
└─────────────────────────────────────────────┘
```

---

## 3. 通信协议

### 3.1 帧格式

```
┌────────────────┬────────────────────┐
│ 4 字节长度头   │   JSON 数据 (UTF-8) │
│ (BigEndian u32)│   N 字节            │
└────────────────┴────────────────────┘
```

单条消息上限 **10MB**。

### 3.2 消息类型

#### 认证
| 类型           | 方向           | 说明       |
| -------------- | -------------- | ---------- |
| LOGIN_REQ      | Client → Server | 登录请求   |
| LOGIN_RSP      | Server → Client | 登录响应   |
| REGISTER_REQ   | Client → Server | 注册请求   |
| REGISTER_RSP   | Server → Client | 注册响应   |

#### 聊天
| 类型           | 方向           | 说明              |
| -------------- | -------------- | ----------------- |
| CHAT_MSG       | 双向           | 聊天消息          |
| SYSTEM_MSG     | Server → Client | 系统通知          |

#### 房间管理
| 类型            | 方向           | 说明              |
| --------------- | -------------- | ----------------- |
| CREATE_ROOM_REQ | Client → Server | 创建房间          |
| CREATE_ROOM_RSP | Server → Client | 创建结果          |
| JOIN_ROOM_REQ   | Client → Server | 加入房间          |
| JOIN_ROOM_RSP   | Server → Client | 加入结果          |
| LEAVE_ROOM      | Client → Server | 离开房间          |
| ROOM_LIST_REQ   | Client → Server | 获取房间列表      |
| ROOM_LIST_RSP   | Server → Client | 房间列表          |
| USER_LIST_REQ   | Client → Server | 获取在线用户      |
| USER_LIST_RSP   | Server → Client | 用户列表          |
| USER_JOINED     | Server → Client | 用户加入通知      |
| USER_LEFT       | Server → Client | 用户离开通知      |

#### 文件传输
| 类型              | 方向           | 说明              |
| ----------------- | -------------- | ----------------- |
| FILE_SEND         | Client → Server | 上传文件(base64)  |
| FILE_NOTIFY       | Server → Client | 文件到达通知      |
| FILE_DOWNLOAD_REQ | Client → Server | 下载请求          |
| FILE_DOWNLOAD_RSP | Server → Client | 下载数据          |

#### 消息撤回
| 类型            | 方向           | 说明              |
| --------------- | -------------- | ----------------- |
| RECALL_REQ      | Client → Server | 撤回请求          |
| RECALL_RSP      | Server → Client | 撤回结果          |
| RECALL_NOTIFY   | Server → Client | 撤回通知(广播)    |

#### 心跳
| 类型            | 方向           | 说明              |
| --------------- | -------------- | ----------------- |
| HEARTBEAT       | Client → Server | 心跳包            |
| HEARTBEAT_ACK   | Server → Client | 心跳确认          |

### 3.3 消息 JSON 结构

```json
{
    "type": "CHAT_MSG",
    "id": "uuid-string",
    "timestamp": 1738000000000,
    "data": {
        "roomId": 1,
        "sender": "alice",
        "content": "Hello!",
        "contentType": "text"
    }
}
```

`contentType` 枚举：`text` | `emoji` | `image` | `file` | `system`

---

## 4. 数据库设计

### 4.1 ER 关系图

```
users 1──N messages
users 1──N room_members
rooms 1──N room_members
rooms 1──N messages
rooms 1──N files
users 1──N files
```

### 4.2 表结构

详见 `database_setup.sql`。

| 表名          | 说明         | 关键字段                          |
| ------------- | ------------ | --------------------------------- |
| users         | 用户表       | username, password_hash, salt     |
| rooms         | 聊天室表     | name, creator_id                  |
| room_members  | 成员关系表   | room_id, user_id (联合主键)       |
| messages      | 消息记录表   | room_id, user_id, content, recalled |
| files         | 文件存储表   | file_name, file_path, file_size   |

### 4.3 密码安全

- 随机 16 字符 salt
- SHA-256(password + salt) 哈希存储
- 不存储明文密码

---

## 5. 核心模块详解

### 5.1 服务器 — ChatServer

- 继承 `QTcpServer`
- `incomingConnection()` 为每个客户端创建独立线程
- 通过跨线程信号/槽与 `ClientSession` 通信
- 使用 `QMutex` 保护共享数据 (`m_sessions`)

### 5.2 服务器 — ClientSession

- 运行在独立 `QThread` 中
- `init()` 在目标线程中创建 `QTcpSocket`
- 实现协议帧的接收和解析
- 心跳超时检测 (90秒无数据断开)

### 5.3 服务器 — DatabaseManager

- 使用 `QSqlDatabase` + QMYSQL 驱动
- 每线程独立数据库连接 (线程安全)
- 连接名格式: `chatroom_conn_<threadId>`
- 支持环境变量配置连接参数

### 5.4 服务器 — RoomManager

- 内存中维护房间和在线成员缓存
- `QMutex` 保护并发访问
- 启动时从数据库加载房间列表

### 5.5 客户端 — NetworkManager (单例)

- 管理到服务器的 TCP 连接
- **心跳机制**: 每 30 秒发送 HEARTBEAT
- **断线重连**: 最多 10 次，间隔 5 秒
- **SSL/TLS**: 可选，通过 `connectToServer(host, port, useSsl)` 启用
- 观察者模式：通过信号分发服务器消息到各 UI 组件

### 5.6 客户端 — MessageModel

- 继承 `QAbstractListModel`
- 自定义 Role 枚举映射消息字段
- 支持追加、前插（历史消息）、撤回更新

### 5.7 客户端 — MessageDelegate

- 继承 `QStyledItemDelegate`
- 自定义绘制消息气泡:
  - **文本消息**: 左右对齐的聊天气泡 + 三角指示 + 头像
  - **系统消息**: 居中灰色圆角矩形
  - **文件消息**: 带文件图标的卡片样式
  - **撤回消息**: 居中斜体提示

### 5.8 客户端 — ThemeManager (单例)

- 深色/浅色主题切换
- 优先加载 `.qss` 资源文件
- 内置默认样式 fallback
- `Ctrl+T` 快捷键切换

### 5.9 客户端 — TrayManager

- 系统托盘图标
- 右键菜单 (显示/退出)
- 双击托盘切换显示/隐藏
- 新消息通知气泡

### 5.10 客户端 — 贴边隐藏

- 300ms 定时检测窗口位置
- 窗口贴近屏幕边缘时自动收起 (露出 4px)
- 鼠标靠近时自动弹出
- 支持左/右/上三个方向

---

## 6. 功能特性清单

| #  | 功能           | 状态 | 说明                                     |
| -- | -------------- | ---- | ---------------------------------------- |
| 1  | 用户注册       | ✅   | MySQL 存储，SHA-256+salt 加密            |
| 2  | 用户登录       | ✅   | 认证后分配 userId                        |
| 3  | 群组聊天室创建 | ✅   | 任何用户可创建                           |
| 4  | 加入/离开房间  | ✅   | 加入时加载历史消息                       |
| 5  | 文本消息       | ✅   | 实时群聊                                 |
| 6  | 表情符号       | ✅   | emoji 选择器，150+ 表情                  |
| 7  | 文件传输       | ✅   | 支持图片/文档，最大 10MB                 |
| 8  | 消息历史       | ✅   | 加入房间时加载最近 50 条                 |
| 9  | 消息撤回       | ✅   | 2 分钟内可撤回自己的消息                 |
| 10 | 多线程连接     | ✅   | 每客户端独立线程                         |
| 11 | JSON 协议      | ✅   | 所有通信使用 JSON                        |
| 12 | SSL/TLS        | ✅   | 可选开启，QSslSocket                     |
| 13 | 心跳机制       | ✅   | 30 秒间隔，90 秒超时                     |
| 14 | 断线重连       | ✅   | 自动重连，最多 10 次                     |
| 15 | 消息气泡       | ✅   | 自定义 QStyledItemDelegate               |
| 16 | 深色/浅色主题  | ✅   | Ctrl+T 切换，QSS 样式表                  |
| 17 | 系统托盘       | ✅   | 托盘图标 + 消息通知                      |
| 18 | 贴边隐藏       | ✅   | 自动检测屏幕边缘                         |

---

## 7. 构建与运行

### 7.1 前置条件

- Qt 5.15+ 或 Qt 6.x (带 QtNetwork, QtSql 模块)
- MySQL 5.7+ 或 MariaDB 10.3+
- MySQL Qt 驱动 (QMYSQL)
- C++17 编译器

### 7.2 数据库准备

```bash
mysql -u root -p < database_setup.sql
```

### 7.3 编译

```bash
# 在项目根目录
mkdir build && cd build
qmake ../ChatRoom.pro
make -j$(nproc)     # Linux/macOS
# 或
nmake               # Windows MSVC
# 或
mingw32-make         # Windows MinGW
```

### 7.4 运行服务器

```bash
./Server/ChatServer -p 9527
```

环境变量配置数据库连接:
```bash
export CHATROOM_DB_HOST=localhost
export CHATROOM_DB_PORT=3306
export CHATROOM_DB_NAME=chatroom
export CHATROOM_DB_USER=root
export CHATROOM_DB_PASS=yourpassword
```

### 7.5 运行客户端

```bash
./Client/ChatClient
```

在登录窗口输入服务器地址、端口、用户名和密码即可连接。

---

## 8. 项目结构

```
ChatRoom/
├── ChatRoom.pro              # 顶层项目文件
├── database_setup.sql        # 数据库初始化脚本
├── DESIGN.md                 # 本设计文档
├── README.md                 # 使用说明
│
├── Common/                   # 共享代码库
│   ├── Common.pri
│   ├── Protocol.h            # 协议定义 & 帧解析
│   ├── Message.h             # 消息数据类
│   └── Message.cpp
│
├── Server/                   # 服务器程序
│   ├── Server.pro
│   ├── main.cpp
│   ├── ChatServer.h/cpp      # TCP 服务器核心
│   ├── ClientSession.h/cpp   # 单客户端会话 (独立线程)
│   ├── DatabaseManager.h/cpp # MySQL 数据库操作
│   └── RoomManager.h/cpp     # 房间 & 在线成员管理
│
└── Client/                   # 客户端程序
    ├── Client.pro
    ├── main.cpp
    ├── NetworkManager.h/cpp   # 网络连接管理 (单例)
    ├── LoginDialog.h/cpp      # 登录/注册 UI
    ├── ChatWindow.h/cpp       # 主聊天窗口
    ├── MessageModel.h/cpp     # 消息列表模型
    ├── MessageDelegate.h/cpp  # 消息气泡绘制
    ├── EmojiPicker.h/cpp      # 表情选择器
    ├── ThemeManager.h/cpp     # 主题管理 (单例)
    ├── TrayManager.h/cpp      # 系统托盘
    └── resources/
        ├── resources.qrc
        ├── light.qss          # 浅色主题样式
        └── dark.qss           # 深色主题样式
```

---

## 9. 扩展方向

- 私聊功能 (一对一消息)
- 语音/视频通话 (WebRTC)
- 消息既读回执
- 用户头像上传
- 富文本消息 (Markdown)
- 消息搜索功能
- 管理员权限 (踢人/禁言)
- 端到端加密
