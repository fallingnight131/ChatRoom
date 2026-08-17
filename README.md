# Qt 聊天室

基于 C++17 / Qt 6 的即时通讯应用，采用 C/S 架构，当前产品支持
**Windows Qt 桌面客户端**和 **Vue Web 客户端**双端互通。

## 功能特性

### 核心功能

- 用户注册 / 登录（新密码使用 libsodium Argon2id，旧 SHA-256 + Salt 在成功登录后渐进升级）
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
- 系统托盘（最小化到托盘、消息通知）—— Windows Qt 端
- 窗口贴边自动隐藏 —— Windows Qt 端
- 断线自动重连 + 自动重新登录
- 心跳保活（30s 间隔 / 90s 超时）
- Web 端响应式布局（桌面 / 平板 / 手机；不代表原生移动客户端支持）
- 同一页面生命周期内断线重认证；刷新页面后需重新登录（不持久化明文密码）
- Windows 端已支持持久化的中文/英文切换，登录注册、个人资料、
  用户信息/头像预览、头像裁剪、房间设置、房间文件管理、受保护房间
  密码加入及密码状态反馈、表情选择器、多目标转发和 V2 会话界面共用同一语言偏好；
  其余旧界面仍在分批迁移
- Windows 登录设备管理已支持实时中英文切换；加载/撤销/非法目录是稳定
  类型状态，切换语言不会改变设备 ID、当前设备保护或正在撤销的请求
- Windows V2 屏蔽账号目录已支持实时中英文切换；本地失败使用稳定类型，
  服务端安全原因保持原样，解除屏蔽重试继续复用同一操作 ID
- Windows V2 新消息通知按当前语言显示隐私安全的通用文案；去重和可见
  会话抑制策略只保存“普通消息/提及”语义，不保存或暴露消息正文
- Windows 系统托盘的应用提示、“显示主窗口”和“退出”操作已接入同一
  语言设置；切换语言只更新展示文本，不重建菜单或改变通知激活路由
- Windows 主窗口标题、顶层菜单、菜单动作和“关于”信息已支持实时
  中英文切换；用户昵称、动作对象、快捷键及功能开关的可见状态保持不变
- Windows 屏蔽目录控制器不再伪造中文“服务端原因”；可重试协议错误使用
  稳定类型交给界面本地化，真正的安全服务端详情仍保持原文
- Windows 主窗口已把服务器连接状态和上传、下载等操作反馈拆成两个状态
  区域；重连不会再覆盖操作进度，连接状态可随语言实时重组
- Windows V1 消息编辑器的表情、文件、输入提示、发送动作和右键换行已
  支持实时中英文；语言切换不重建输入框，不影响草稿、选区或光标
- Windows 房间/好友导航控件和好友在线状态已支持实时中英文；好友身份、
  选择、未读数和头像保持稳定，在线状态不再拼进持久展示数据

### 双端互通

- Windows Qt 桌面客户端通过 TCP 连接（默认端口 9527）
- Vue Web 客户端通过 WebSocket 连接（默认端口 9528）
- 同一服务器同时处理 TCP 和 WebSocket 连接
- 两端用户可在同一房间实时通信

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | C++17 / Java 21 / JavaScript (ES2020+) |
| GUI 框架 | Qt 6.11.1 (Widgets) |
| Web 框架 | Vue 3 + Vite 5 + Pinia + Vue Router |
| 视频播放 | DPlayer 1.27 |
| 网络 | QTcpServer / QTcpSocket / QWebSocketServer |
| 数据库 | SQLite（Qt 内置驱动，零配置） |
| 构建 | qmake (Qt) / Vite (Web) |
| 协议 | TCP: 自定义二进制帧（4 字节长度头 + JSON）<br>WebSocket: 纯 JSON 文本帧 |

Windows 产品构建固定使用 Qt 6.11.1；Linux qmake 可移植性、CMake
无界面服务端和数据库验证同时保持对 Ubuntu 系统 Qt 6.4 API 范围的
编译兼容性。Linux/macOS 上的 Qt Widgets 可移植性测试默认使用
`offscreen` 平台，无需图形桌面；Windows 原生交互仍由 Windows 产品门禁验证。
PostgreSQL 真实集成测试的时间夹具对齐 `timestamptz` 的微秒精度，
避免把 Java 纳秒值与数据库持久值误判为凭证过期契约变化。
Windows V2 会话目录的单击打开路径不再依赖“当前行发生变化”，
首行已是当前行时仍会按隐藏会话标识正确打开会话。
Windows CI 保持 Qt 6.11.1 产品基线，并固定使用已支持 Qt 6.11
架构分目录的 aqtinstall 上游提交，避免误访问旧版双层元数据路径。

## 项目结构

```
ChatRoom/
├── ChatRoom.pro          # 顶层 qmake subdirs 项目
├── README.md             # 项目说明
├── AGENTS.md             # 工程和架构演进规则
├── docs/                 # 权威架构、协议、数据与验证文档
├── Common/               # 共享协议层
│   ├── Protocol.h        # 消息协议定义（当前 125 种消息类型）
│   └── Message.h/cpp     # 消息数据模型
├── Server/               # 服务端（控制台程序，TCP + WebSocket 双协议）
│   ├── ChatServer        # TCP + WebSocket 服务器
│   ├── ClientSession     # 客户端会话（支持 TCP/WebSocket 双传输层）
│   ├── DatabaseManager   # SQLite 数据库操作
│   └── RoomManager       # 聊天室管理
├── Client/               # Windows Qt 桌面客户端（GUI 程序）
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

## 架构演进

项目的长期目标架构、可靠消息模型、Java 后端迁移、Web/Windows 客户端和安装包路线，统一维护在：

- [架构总览](docs/architecture/README.md)
- [当前系统基线](docs/architecture/CURRENT_SYSTEM.md)
- [V1 协议基线](docs/protocol/V1_PROTOCOL.md)
- [V1 SQLite 基线](docs/data/V1_SQLITE_SCHEMA.md)
- [可重复构建与验证](docs/BUILDING.md)
- [迭代路线图](docs/architecture/ROADMAP.md)
- [M1 验收记录](docs/baselines/M1_ACCEPTANCE_2026-08-11.md)
- [架构决策记录](docs/architecture/decisions/)
- [Codex/Agent 工程约定](AGENTS.md)

架构演进遵循兼容优先、纵向切片、可测量、可回滚原则，不进行一次性全量重写。

## 环境要求

### Windows Qt 端

- Qt 6.11.1（Windows x64 + MSVC 2022 产品构建基线）
- C++17 编译器
- qmake（当前 Windows 产品构建）
- CMake 3.24+（增量 HeadlessServer 验证路径）

macOS 和 Linux 可以作为开发或服务端验证环境，但不是当前客户端产品
支持范围。

### Web 端

- Node.js 22
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

建议先使用统一验证入口确认本机工具链：

```bash
python3 tools/verify_m0.py --web --db-schema --v1-smoke --performance
```

源码清单校验会统一 Windows、macOS 与 Linux 的路径分隔符和文本换行，
因此同一 Git 修订在不同 Runner 上应得到相同的 M0 指纹。
Java V2 协议验证中的 Windows ViewModel 会显式链接共享的本地化目录，
以确保协议生成后执行的 C++ 链接检查与客户端实际依赖保持一致。

服务端 CMake 增量路径可在 macOS 开发机上单独验证：

```bash
SODIUM_ROOT="$(brew --prefix libsodium)" \
python3 tools/verify_m0.py --cmake-headless
```

Windows Qt 产品构建、非产品开发主机命令、依赖与已知工具链边界见
[构建指南](docs/BUILDING.md)和[支持矩阵](docs/architecture/SUPPORT_MATRIX.md)。

### 1. 构建 Windows Qt 服务端和客户端

```powershell
# 在安装了 Qt 6.11.1 MSVC 2022 和 Visual Studio 2022 的开发终端中运行
vcpkg install --triplet x64-windows
$env:SODIUM_ROOT = (Resolve-Path .\vcpkg_installed\x64-windows).Path.Replace('\', '/')

python tools/verify_m0.py --qt
```

### 2. 启动 Web 客户端（开发模式）

```bash
cd WebClient
npm ci
npm run dev
```

### 3. 运行

```powershell
# 启动服务端（默认 TCP 9527 + WebSocket 9528）
.\build\m0\windows\server\release\ChatServer.exe

# 启动 Windows Qt 客户端（可同时启动多个）
.\build\m0\windows\client\release\ChatClient.exe

# Web 客户端访问 http://localhost:5173
```

Qt 客户端默认连接 `127.0.0.1:9527`（TCP），Web 客户端默认连接 `127.0.0.1:9528`（WebSocket）。

### 4. 指定端口

```powershell
# TCP 端口 8888，WebSocket 端口自动为 8889
ChatServer.exe --port 8888

# 分别指定
ChatServer.exe --port 8888 --ws-port 9999
```

---

## 部署状态

项目当前不提供可信的公网生产部署脚本。旧脚本会在生产机上以 `root`
身份现场编译、暴露明文 TCP/WebSocket 端口，且没有签名产物、回滚和完整
密钥管理，已从仓库移除。

M1 已完成代码库内的认证、授权和可靠消息基线，但公网部署仍必须在
受信边缘终止 TLS/WSS，并完成密钥、监控和回滚策略。Windows 签名安装包、
自动更新以及 Web 的可回滚发布属于 M4。

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

服务端与客户端通过 JSON 消息通信，当前共声明 125 种消息类型。完整、以代码为准的清单见 [V1 协议基线](docs/protocol/V1_PROTOCOL.md)：

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
