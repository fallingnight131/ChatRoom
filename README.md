# Qt 聊天室

基于 C++17 / Qt 6 的即时通讯应用，采用 C/S 架构，支持多人实时聊天。

## 功能特性

- 用户注册 / 登录（SHA-256 + Salt 密码加密）
- 多聊天室（创建 / 加入 / 切换）
- 实时消息收发，自定义气泡渲染
- 表情选择器（150+ Emoji）
- 图片 / 文件发送
- 消息撤回（2 分钟内）
- 聊天记录存储与加载
- 亮色 / 暗色主题切换（Ctrl+T）
- 系统托盘（最小化到托盘、消息通知）
- 窗口贴边自动隐藏
- 断线自动重连

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | C++17 |
| GUI 框架 | Qt 6.7+ (Widgets) |
| 网络 | QTcpServer / QTcpSocket |
| 数据库 | SQLite（Qt 内置驱动，零配置） |
| 构建 | qmake |
| 协议 | 自定义二进制帧（4 字节长度头 + JSON） |

## 项目结构

```
ChatRoom/
├── ChatRoom.pro          # 顶层 qmake subdirs 项目
├── Common/               # 共享协议层
│   ├── Protocol.h        # 消息协议定义
│   └── Message.h/cpp     # 消息数据模型
├── Server/               # 服务端（控制台程序）
│   ├── ChatServer        # TCP 服务器
│   ├── ClientSession     # 客户端会话（每连接一个线程）
│   ├── DatabaseManager   # SQLite 数据库操作
│   └── RoomManager       # 聊天室管理
└── Client/               # 客户端（GUI 程序）
    ├── NetworkManager    # 网络连接管理（单例）
    ├── LoginDialog       # 登录/注册界面
    ├── ChatWindow        # 主聊天窗口
    ├── MessageModel      # 消息数据模型 (MVC)
    ├── MessageDelegate   # 消息气泡渲染
    ├── EmojiPicker       # 表情选择器
    ├── ThemeManager      # 主题管理
    ├── TrayManager       # 系统托盘管理
    └── resources/        # QSS 样式表
```

## 环境要求

- Qt 6.7+（MinGW 或 MSVC 均可）
- C++17 编译器
- qmake

> SQLite 驱动已内置于 Qt，**无需安装任何数据库**。首次运行 Server 时会自动创建 `chatroom.db`。

---

## 本地运行

### 1. 构建

```powershell
# 将 Qt 和 MinGW 加入 PATH（根据你的安装路径调整）
$env:PATH = "D:\ProgramFiles\QT\6.7.2\mingw_64\bin;D:\ProgramFiles\QT\Tools\mingw1120_64\bin;$env:PATH"

# 构建 Server
cd Server
qmake Server.pro -spec win32-g++ "CONFIG+=release"
mingw32-make -j8

# 构建 Client
cd ..\Client
qmake Client.pro -spec win32-g++ "CONFIG+=release"
mingw32-make -j8
```

### 2. 运行

```powershell
# 先启动服务端（默认监听端口 9527）
.\Server\release\ChatServer.exe

# 再启动客户端（可同时启动多个）
.\Client\release\ChatClient.exe
```

客户端打开后直接输入用户名和密码注册/登录即可，默认连接 `127.0.0.1:9527`。

如需连接其他服务器，点击登录页底部 **"▶ 高级设置"** 展开服务器地址配置。

### 3. 指定端口

```powershell
ChatServer.exe --port 8888
```

---

## 云服务器部署

### 方案一：Linux 服务器（推荐）

适用于阿里云、腾讯云、AWS 等 Linux 云主机。只需部署 Server，Client 在用户本地运行。

#### 1) 安装依赖

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install qt6-base-dev build-essential

# CentOS / Rocky Linux
sudo dnf install qt6-qtbase-devel gcc-c++ make
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
sudo ufw allow 9527/tcp

# CentOS (firewalld)
sudo firewall-cmd --permanent --add-port=9527/tcp
sudo firewall-cmd --reload
```

> 同时在云控制台的 **安全组** 中放行 TCP 9527 端口。

#### 6) 客户端连接

客户端登录时展开 **"高级设置"**，将服务器地址改为云主机公网 IP，端口填 `9527`。

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
RUN apt-get update && apt-get install -y qt6-base-dev build-essential
COPY Common/ /app/Common/
COPY Server/ /app/Server/
WORKDIR /app/Server
RUN qmake6 Server.pro "CONFIG+=release" && make -j$(nproc)
EXPOSE 9527
CMD ["./ChatServer", "--port", "9527"]
```

```bash
docker build -t chatroom-server .
docker run -d -p 9527:9527 --name chatroom chatroom-server
```

---

## 设计模式

| 模式 | 应用 |
|------|------|
| 观察者模式 | NetworkManager 信号/槽分发消息 |
| 单例模式 | NetworkManager、ThemeManager |
| MVC 模式 | MessageModel + MessageDelegate + QListView |
| 策略模式 | 主题切换（Light/Dark QSS） |
| 工厂模式 | Message::createXxxMessage() 系列方法 |

## License

MIT
