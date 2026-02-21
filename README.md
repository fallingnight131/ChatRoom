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
- 视频文件缩略图预览

### 用户系统

- 用户头像上传与裁剪（256×256 PNG）
- 个人资料编辑（昵称 / 用户 ID / 密码）
- 在线 / 离线状态显示
- 用户信息弹窗（头像、角色、状态）
- 强制下线（同一账号异地登录）

### 管理员功能

- 房间管理员设置
- 踢出用户
- 删除消息（单条 / 全部清空）
- 房间设置（文件大小限制）
- 查看 / 修改房间密码

### 界面体验

- 亮色 / 暗色主题切换
- 系统托盘（最小化到托盘、消息通知）—— Qt 端
- 窗口贴边自动隐藏 —— Qt 端
- 断线自动重连
- 心跳保活（30s 间隔 / 90s 超时）

### 双端互通

- Qt 桌面客户端通过 TCP 连接（端口 9527）
- Vue Web 客户端通过 WebSocket 连接（端口 9528）
- 同一服务器同时处理 TCP 和 WebSocket 连接
- 两端用户可在同一房间实时通信

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | C++17 / JavaScript (ES2020+) |
| GUI 框架 | Qt 6.7+ (Widgets) |
| Web 框架 | Vue 3 + Vite 5 + Pinia + Vue Router |
| 网络 | QTcpServer / QTcpSocket / QWebSocketServer |
| 数据库 | SQLite（Qt 内置驱动，零配置） |
| 构建 | qmake (Qt) / Vite (Web) |
| 协议 | TCP: 自定义二进制帧（4 字节长度头 + JSON）<br>WebSocket: 纯 JSON 文本帧 |

## 项目结构

```
ChatRoom/
├── ChatRoom.pro          # 顶层 qmake subdirs 项目
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

### 方案一：Linux 服务器（推荐）

适用于阿里云、腾讯云、AWS 等 Linux 云主机。只需部署 Server，客户端在用户本地运行或浏览器访问。

#### 1) 安装依赖

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install qt6-base-dev qt6-websockets-dev build-essential

# CentOS / Rocky Linux
sudo dnf install qt6-qtbase-devel qt6-qtwebsockets-devel gcc-c++ make
```

#### 2) 上传源码并编译

```bash
# 将 Common/ 和 Server/ 上传到服务器
scp -r Common/ Server/ user@your-server:/opt/chatroom/

# SSH 登录后编译
ssh user@your-server
cd /opt/chatroom/Server
qmake6 Server.pro "CONFIG+=release"
make -j$(nproc)
```

#### 3) 运行

```bash
# 前台运行（测试）
./ChatServer --port 9527

# 后台运行（生产）
nohup ./ChatServer --port 9527 > server.log 2>&1 &
```

#### 4) 创建 systemd 服务（推荐）

```bash
sudo tee /etc/systemd/system/chatroom.service << 'EOF'
[Unit]
Description=Qt ChatRoom Server
After=network.target

[Service]
Type=simple
User=chatroom
WorkingDirectory=/opt/chatroom/Server
ExecStart=/opt/chatroom/Server/ChatServer --port 9527
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable chatroom
sudo systemctl start chatroom
```

#### 5) 防火墙放行

```bash
# Ubuntu (ufw)
sudo ufw allow 9527/tcp    # Qt 客户端
sudo ufw allow 9528/tcp    # Web 客户端 WebSocket

# CentOS (firewalld)
sudo firewall-cmd --permanent --add-port=9527-9528/tcp
sudo firewall-cmd --reload
```

> 同时在云控制台的 **安全组** 中放行 TCP 9527 和 9528 端口。

#### 6) 部署 Web 客户端（可选）

```bash
# 在本地构建 Web 客户端
cd WebClient
npm install
npm run build

# 将 dist/ 部署到 Nginx
scp -r dist/ user@your-server:/var/www/chatroom/

# Nginx 配置示例
server {
    listen 80;
    server_name chat.example.com;
    root /var/www/chatroom;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

#### 7) 客户端连接

- **Qt 客户端**：登录时展开 "高级设置"，服务器地址填写公网 IP，端口 `9527`
- **Web 客户端**：打开浏览器，点击 ⚙ 服务器设置，填写公网 IP 和 WebSocket 端口 `9528`

### 方案二：Windows Server

```powershell
# 使用 windeployqt 打包依赖
windeployqt.exe ChatServer.exe

# 将整个文件夹复制到 Windows Server，直接运行
ChatServer.exe --port 9527
```

### 方案三：Docker

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y qt6-base-dev qt6-websockets-dev build-essential
COPY Common/ /app/Common/
COPY Server/ /app/Server/
WORKDIR /app/Server
RUN qmake6 Server.pro "CONFIG+=release" && make -j$(nproc)
EXPOSE 9527 9528
CMD ["./ChatServer", "--port", "9527"]
```

```bash
docker build -t chatroom-server .
docker run -d -p 9527:9527 -p 9528:9528 --name chatroom chatroom-server
```

---

## 设计模式

| 模式 | 应用 |
|------|------|
| 观察者模式 | NetworkManager 信号/槽分发消息 |
| 单例模式 | NetworkManager、ThemeManager |
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
