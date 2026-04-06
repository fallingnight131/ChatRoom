# Qt 聊天室

基于 C++17 / Qt 6 的即时通讯应用，采用 C/S 架构，支持 **Qt 桌面客户端** 和 **Vue Web 客户端** 双端互通。

## 功能特性

### 核心功能

- 用户注册 / 登录（SHA-256 + Salt 密码加密）
- 唯一用户 ID（可修改，30 天冷却）+ 可变昵称
- 多聊天室（创建 / 加入 / 退出 / 删除 / 重命名 / 密码保护）
- 实时消息收发，自定义气泡渲染
- 表情选择器（96 个 Emoji，8×12 网格）
- 消息撤回（2 分钟内）
- 聊天记录存储与加载（上滑加载更多）

### 文件传输

- 图片 / 文件发送（自动识别类型）
- 小文件直传（≤8MB）
- 大文件分块传输（4MB/块，最大 4GB）
- 上传 / 下载暂停、恢复、取消
- 图片 / 视频缩略图预览
- Web 端文件预览（图片缩放拖拽、视频 DPlayer 播放、PDF / 文本 / 音频在线预览）

### 用户系统

- 用户头像上传与裁剪（256×256 PNG）
- 个人资料编辑（昵称 / 用户 ID / 密码）
- 在线 / 离线状态显示
- 用户信息弹窗（头像、角色、状态）
- 强制下线（同一账号异地登录）

### 管理员功能

- 房间管理员设置 / 取消
- 踢出用户
- 删除消息（单条 / 全部清空 / 按日期范围）
- 房间设置（文件大小限制、房间密码）
- 删除房间

### 界面体验

- 亮色 / 暗色主题切换（Qt QSS + Web CSS 变量）
- 系统托盘（最小化到托盘、消息通知）—— Qt 端
- 窗口贴边自动隐藏 —— Qt 端
- 断线自动重连 + 自动重新登录
- 心跳保活（30s 间隔 / 90s 超时）
- Web 端响应式布局（桌面 / 平板 / 手机）
- 刷新页面保持登录状态

### 双端互通

- Qt 桌面客户端通过 TCP 连接（默认端口 9527）
- Vue Web 客户端通过 WebSocket 连接（默认端口 9528）
- 同一服务器同时处理 TCP 和 WebSocket 连接
- 两端用户可在同一房间实时通信

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | C++17 / JavaScript (ES2020+) |
| GUI 框架 | Qt 6.7+ (Widgets) |
| Web 框架 | Vue 3 + Vite 5 + Pinia + Vue Router |
| 视频播放 | DPlayer 1.27 |
| 网络 | QTcpServer / QTcpSocket / QWebSocketServer |
| 数据库 | SQLite（Qt 内置驱动，零配置） |
| 构建 | qmake (Qt) / Vite (Web) |
| 协议 | TCP: 自定义二进制帧（4 字节长度头 + JSON）<br>WebSocket: 纯 JSON 文本帧 |

## 项目结构

```
ChatRoom/
├── ChatRoom.pro          # 顶层 qmake subdirs 项目
├── README.md             # 项目说明
├── DEPLOY.md             # 云服务器部署指南
├── DESIGN.md             # 详细设计文档
├── Common/               # 共享协议层
│   ├── Protocol.h        # 消息协议定义（56 种消息类型）
│   └── Message.h/cpp     # 消息数据模型
├── Server/               # 服务端（控制台程序，TCP + WebSocket 双协议）
│   ├── ChatServer        # TCP + WebSocket 服务器
│   ├── ClientSession     # 客户端会话（支持 TCP/WebSocket 双传输层）
│   ├── DatabaseManager   # SQLite 数据库操作
│   └── RoomManager       # 聊天室管理
├── Client/               # Qt 桌面客户端（GUI 程序）
│   ├── NetworkManager    # 网络连接管理（单例）
│   ├── LoginDialog       # 登录/注册界面
│   ├── ChatWindow        # 主聊天窗口
│   ├── MessageModel      # 消息数据模型 (MVC)
│   ├── MessageDelegate   # 消息气泡渲染
│   ├── EmojiPicker       # 表情选择器
│   ├── FileCache         # 文件缓存管理
│   ├── ThemeManager      # 主题管理
│   ├── TrayManager       # 系统托盘管理
│   └── resources/        # QSS 样式表
└── WebClient/            # Vue Web 客户端
    ├── package.json      # 依赖管理
    ├── vite.config.js    # Vite 构建配置
    └── src/
        ├── main.js           # Vue 应用入口
        ├── App.vue           # 根组件（深/浅主题）
        ├── assets/style.css  # 全局样式（CSS 变量主题系统）
        ├── router/           # 路由配置
        ├── services/         # WebSocket 服务层
        ├── stores/           # Pinia 状态管理（user, chat）
        ├── views/            # 页面（Login, Chat）
        └── components/       # UI 组件
            ├── RoomList.vue          # 房间列表
            ├── MessageList.vue       # 消息列表与气泡
            ├── UserList.vue          # 成员列表
            ├── InputArea.vue         # 输入区域
            ├── EmojiPicker.vue       # 表情选择器
            ├── FilePreview.vue       # 文件预览（图片/视频/PDF/音频/文本）
            ├── ProfileDialog.vue     # 个人资料
            ├── RoomSettingsDialog.vue # 房间设置
            ├── UserInfoDialog.vue    # 用户信息
            └── RoomPasswordDialog.vue # 密码输入
```

## 环境要求

### Qt 端

- Qt 6.7+（MinGW 或 MSVC）
- C++17 编译器
- qmake

### Web 端

- Node.js 18+
- npm

> SQLite 驱动已内置于 Qt，**无需安装任何数据库**。首次运行 Server 时会自动创建 `chatroom.db`。

## 环境变量（.env）

聊天室部分操作需要通过“开发者秘钥”执行
服务端的“开发者秘钥”通过 `.env` 配置，键名为 `CHATROOM_DEVELOPER_KEY`。

### 1. 复制模板

```bash
cp .env.example .env
```

Windows PowerShell 可使用：

```powershell
Copy-Item .env.example .env
```

### 2. 编辑 `.env`

```env
CHATROOM_DEVELOPER_KEY=请替换成你的强密码
```

---

## 本地运行

### 1. 构建服务端

```powershell
# 将 Qt 和 MinGW 加入 PATH（根据你的安装路径调整）
$env:PATH = "D:\ProgramFiles\QT\6.7.2\mingw_64\bin;D:\ProgramFiles\QT\Tools\mingw1120_64\bin;$env:PATH"

# 构建 Server
cd Server
qmake Server.pro -spec win32-g++ "CONFIG+=release"
mingw32-make -j8
```

### 2. 构建 Qt 客户端

```powershell
cd Client
qmake Client.pro -spec win32-g++ "CONFIG+=release"
mingw32-make -j8
```

### 3. 启动 Web 客户端（开发模式）

```bash
cd WebClient
npm install
npm run dev
```

### 4. 运行

```powershell
# 启动服务端（默认 TCP 9527 + WebSocket 9528）
.\Server\release\ChatServer.exe

# 启动 Qt 客户端（可同时启动多个）
.\Client\release\ChatClient.exe

# Web 客户端访问 http://localhost:5173
```

Qt 客户端默认连接 `127.0.0.1:9527`（TCP），Web 客户端默认连接 `127.0.0.1:9528`（WebSocket）。

### 5. 指定端口

```powershell
# TCP 端口 8888，WebSocket 端口自动为 8889
ChatServer.exe --port 8888

# 分别指定
ChatServer.exe --port 8888 --ws-port 9999
```

---

## 云服务器部署

详见 [DEPLOY.md](DEPLOY.md)，包含完整的 Linux 服务器部署教程（编译、systemd 服务、Nginx 反向代理、SSL/HTTPS 配置）。

---

## 设计模式

| 模式 | 应用 |
|------|------|
| 观察者模式 | NetworkManager 信号/槽分发消息 |
| 单例模式 | NetworkManager、ThemeManager、FileCache |
| MVC 模式 | MessageModel + MessageDelegate + QListView |
| 策略模式 | 主题切换（Light/Dark QSS / CSS 变量） |
| 工厂模式 | Message::createXxxMessage() 系列方法 |

## 协议概览

服务端与客户端通过 JSON 消息通信，共 56 种消息类型：

| 类别 | 消息类型 |
|------|---------|
| 认证 | LOGIN_REQ/RSP, REGISTER_REQ/RSP, LOGOUT, FORCE_OFFLINE |
| 聊天 | CHAT_MSG, SYSTEM_MSG |
| 房间 | CREATE/JOIN/LEAVE/DELETE/RENAME_ROOM, ROOM_LIST, ROOM_SETTINGS, SET/GET_ROOM_PASSWORD |
| 用户 | USER_LIST, USER_JOINED/LEFT/ONLINE/OFFLINE |
| 文件 | FILE_SEND/NOTIFY/DOWNLOAD, UPLOAD_START/CHUNK/END/CANCEL, DOWNLOAD_CHUNK |
| 撤回 | RECALL_REQ/RSP/NOTIFY |
| 管理 | SET_ADMIN, KICK_USER, DELETE_MSGS |
| 头像 | AVATAR_UPLOAD/GET/UPDATE_NOTIFY |
| 个人 | CHANGE_NICKNAME/UID/PASSWORD |
| 心跳 | HEARTBEAT/HEARTBEAT_ACK |

- **TCP 传输层**（Qt 客户端）：`[4字节大端长度][JSON]` 二进制帧
- **WebSocket 传输层**（Web 客户端）：纯 JSON 文本消息

## License

MIT
