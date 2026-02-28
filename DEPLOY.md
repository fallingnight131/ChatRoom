# 云服务器部署指南

本指南以 **阿里云 Linux 服务器 + 宝塔面板 + Nginx** 为例，详细说明如何将 Qt 聊天室部署到公网。其他云厂商（腾讯云、华为云、AWS 等）流程类似。

---

## 目录

- [1. 准备工作](#1-准备工作)
- [2. 服务器环境配置](#2-服务器环境配置)
- [3. 编译服务端](#3-编译服务端)
- [4. 创建 systemd 服务](#4-创建-systemd-服务)
- [5. 部署 Web 客户端](#5-部署-web-客户端)
- [6. Nginx 反向代理配置](#6-nginx-反向代理配置)
- [7. SSL 证书配置（HTTPS）](#7-ssl-证书配置https)
- [8. 防火墙与安全组](#8-防火墙与安全组)
- [9. DNS 解析](#9-dns-解析)
- [10. 客户端连接配置](#10-客户端连接配置)
- [11. 部署脚本（快速部署）](#11-部署脚本快速部署)
- [12. 运维管理](#12-运维管理)
- [13. 常见问题排查](#13-常见问题排查)

---

## 1. 准备工作

### 1.1 所需资源

| 项目 | 说明 |
|------|------|
| 云服务器 | 1 核 2G 即可，推荐 2 核 4G |
| 操作系统 | Alibaba Cloud Linux 3 / Ubuntu 22.04 / CentOS Stream 9 |
| 域名（可选） | 用于 HTTPS 和更友好的访问地址 |
| SSL 证书（可选） | Let's Encrypt 免费证书 或 云厂商免费证书 |

### 1.2 服务端口规划

| 端口 | 用途 | 是否对外暴露 |
|------|------|-------------|
| 9527 | ChatServer TCP（Qt 客户端） | ✅ 直接暴露或 Nginx 四层代理 |
| 9528 | ChatServer WebSocket（Web 客户端） | ❌ 通过 Nginx 反向代理 |
| 80 | Nginx HTTP | ✅ |
| 443 | Nginx HTTPS | ✅ |

### 1.3 本地准备

在本地 Windows 开发机上提前构建 Web 客户端：

```powershell
cd WebClient
npm install
npm run build
# 输出目录：WebClient/dist/
```

---

## 2. 服务器环境配置

### 2.1 安装宝塔面板（推荐）

```bash
# Alibaba Cloud Linux 3 / CentOS
curl -sSO https://download.bt.cn/install/install_panel.sh && bash install_panel.sh ed8484bec

# Ubuntu / Debian
curl -sSO https://download.bt.cn/install/install-ubuntu_6.0.sh && bash install-ubuntu_6.0.sh ed8484bec
```

安装完成后，按提示访问宝塔面板，安装 **Nginx**（推荐编译安装最新版）。

> 如果不使用宝塔面板，也可以手动安装 Nginx：`sudo apt install nginx` 或 `sudo dnf install nginx`。

### 2.2 安装 Qt 开发依赖

根据你的系统选择对应命令：

```bash
# ===== Alibaba Cloud Linux 3 / CentOS Stream 9 =====
sudo dnf install -y epel-release
sudo dnf install -y qt5-qtbase-devel qt5-qtwebsockets-devel gcc-c++ make

# ===== Ubuntu 22.04 =====
sudo apt update
sudo apt install -y qt6-base-dev qt6-websockets-dev build-essential

# ===== Ubuntu 20.04（较旧，只有 Qt5）=====
sudo apt update
sudo apt install -y qtbase5-dev libqt5websockets5-dev build-essential
```

> **注意**：Alibaba Cloud Linux 3 和 CentOS 系统通常只有 Qt5 包。本项目兼容 Qt5/Qt6，代码通用。Ubuntu 22.04+ 可直接安装 Qt6。

### 2.3 验证安装

```bash
qmake --version    # 或 qmake-qt5 / qmake6，取决于系统
g++ --version
```

如果 `qmake` 命令不存在，尝试 `qmake-qt5`（CentOS）或 `qmake6`（Ubuntu）。后续编译命令中相应替换即可。

---

## 3. 编译服务端

### 3.1 上传源码

将项目的 `Common/` 和 `Server/` 目录上传到服务器。推荐使用宝塔面板的文件管理器上传压缩包，或使用 scp：

```bash
# 本地执行
scp -r Common/ Server/ root@你的服务器IP:/opt/chatroom/
```

### 3.2 编译

```bash
ssh root@你的服务器IP
cd /opt/chatroom/Server

# 根据系统中 qmake 的实际命令调整
qmake-qt5 Server.pro "CONFIG+=release"   # CentOS / Alibaba Cloud Linux
# 或 qmake6 Server.pro "CONFIG+=release"  # Ubuntu 22.04+
# 或 qmake Server.pro "CONFIG+=release"   # 通用

make -j$(nproc)
```

编译成功后会在当前目录生成 `ChatServer` 可执行文件。

### 3.3 测试运行

```bash
./ChatServer --port 9527
```

看到类似以下输出说明服务端启动成功：

```
TCP Server listening on port 9527
WebSocket Server listening on port 9528
Database initialized successfully
```

按 `Ctrl+C` 停止测试。

---

## 4. 创建 systemd 服务

使用 systemd 管理服务端进程，实现开机自启和自动重启。

### 4.1 创建服务用户（安全起见）

```bash
sudo useradd -r -s /bin/false chatroom
sudo chown -R chatroom:chatroom /opt/chatroom/
```

### 4.2 创建 service 文件

```bash
sudo tee /etc/systemd/system/chatroom.service << 'EOF'
[Unit]
Description=Qt ChatRoom Server
After=network.target

[Service]
Type=simple
User=chatroom
Group=chatroom
WorkingDirectory=/opt/chatroom/Server
ExecStart=/opt/chatroom/Server/ChatServer --port 9527
Restart=always
RestartSec=5
LimitNOFILE=65536

# 日志输出到 journal
StandardOutput=journal
StandardError=journal
SyslogIdentifier=chatroom

[Install]
WantedBy=multi-user.target
EOF
```

### 4.3 启动服务

```bash
sudo systemctl daemon-reload
sudo systemctl enable chatroom      # 开机自启
sudo systemctl start chatroom       # 立即启动
sudo systemctl status chatroom      # 查看状态
```

### 4.4 查看日志

```bash
# 实时查看日志
sudo journalctl -u chatroom -f

# 查看最近 100 行
sudo journalctl -u chatroom -n 100
```

---

## 5. 部署 Web 客户端

### 5.1 上传构建产物

将本地构建好的 `WebClient/dist/` 目录上传到服务器：

```bash
# 方式一：scp
scp -r WebClient/dist/ root@你的服务器IP:/www/wwwroot/chat/

# 方式二：宝塔面板
# 在宝塔文件管理器中，将 dist/ 目录上传到 /www/wwwroot/chat/
```

### 5.2 目录结构

上传后服务器目录应如下：

```
/www/wwwroot/chat/
├── index.html
├── assets/
│   ├── index-xxxx.js
│   └── index-xxxx.css
└── ...
```

---

## 6. Nginx 反向代理配置

Nginx 承担两个职责：
1. **静态文件托管**：提供 Web 客户端的 HTML/JS/CSS
2. **WebSocket 反向代理**：将 `/ws` 路径代理到 ChatServer 的 WebSocket 端口 (9528)

### 6.1 宝塔面板操作

1. 在宝塔面板中 **添加站点**（填写域名或 IP）
2. 站点目录设为 `/www/wwwroot/chat`
3. 进入站点设置 → **配置文件**，按照下方模板修改

### 6.2 Nginx 配置模板

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name chat.example.com;  # 替换为你的域名或公网 IP

    # Web 客户端静态文件
    root /www/wwwroot/chat;
    index index.html;

    # Vue Router history 模式（本项目用 hash 模式，此行作为兼容保留）
    location / {
        try_files $uri $uri/ /index.html;
    }

    # WebSocket 反向代理
    location /ws {
        proxy_pass http://127.0.0.1:9528;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 超时设置（WebSocket 长连接需要较长超时）
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;

        # 关闭缓冲
        proxy_buffering off;
    }

    # 文件上传大小限制
    client_max_body_size 100m;

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, no-transform";
    }

    # 禁止访问隐藏文件
    location ~ /\. {
        deny all;
    }
}
```

### 6.3 重载 Nginx

```bash
sudo nginx -t            # 测试配置
sudo nginx -s reload     # 重载配置
```

### 6.4 Web 客户端 WebSocket 连接地址

配置 Nginx 反代后，Web 客户端连接时：

- **非 SSL**：`ws://chat.example.com/ws`
- **SSL**：`wss://chat.example.com/ws`

在 Web 客户端登录页的服务器设置中填写：
- 主机：`chat.example.com`（你的域名）
- 端口：`80`（HTTP）或 `443`（HTTPS）
- 路径：`/ws`

> Web 客户端代码中 WebSocket 连接 URL 需与 Nginx 的 `location /ws` 路径匹配。如果你的 Web 客户端代码直连 9528 端口，则可以不做反代，但无法享受 HTTPS 加密。

---

## 7. SSL 证书配置（HTTPS）

生产环境**强烈建议**配置 SSL，否则 WebSocket 连接在 HTTPS 页面上会被浏览器拒绝。

### 7.1 宝塔面板一键配置（推荐）

1. 站点设置 → **SSL** → **Let's Encrypt**
2. 填写域名，点击申请
3. 申请成功后开启 **强制 HTTPS**

### 7.2 手动配置 Let's Encrypt

```bash
# 安装 certbot
sudo apt install certbot python3-certbot-nginx   # Ubuntu
sudo dnf install certbot python3-certbot-nginx    # CentOS

# 申请证书
sudo certbot --nginx -d chat.example.com

# 自动续期（certbot 默认已设置定时任务）
sudo certbot renew --dry-run
```

### 7.3 HTTPS Nginx 配置示例

申请证书后，Nginx 配置会自动更新为类似以下内容：

```nginx
server {
    listen 80;
    server_name chat.example.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name chat.example.com;

    ssl_certificate /etc/letsencrypt/live/chat.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    root /www/wwwroot/chat;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /ws {
        proxy_pass http://127.0.0.1:9528;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
        proxy_buffering off;
    }

    client_max_body_size 100m;
}
```

---

## 8. 防火墙与安全组

### 8.1 服务器防火墙

```bash
# ===== Ubuntu (ufw) =====
sudo ufw allow 80/tcp       # HTTP
sudo ufw allow 443/tcp      # HTTPS
sudo ufw allow 9527/tcp     # Qt 客户端 TCP 直连
# 注意：9528 端口由 Nginx 反代，无需对外暴露

# ===== CentOS / Alibaba Cloud Linux (firewalld) =====
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-port=9527/tcp
sudo firewall-cmd --reload
```

### 8.2 云控制台安全组

登录云控制台（如阿里云 ECS 控制台），在**安全组规则**中添加：

| 方向 | 协议 | 端口范围 | 授权对象 | 说明 |
|------|------|---------|---------|------|
| 入方向 | TCP | 80 | 0.0.0.0/0 | HTTP |
| 入方向 | TCP | 443 | 0.0.0.0/0 | HTTPS |
| 入方向 | TCP | 9527 | 0.0.0.0/0 | Qt 客户端 TCP |

> 宝塔面板端口（默认 8888 等）建议限制来源 IP，不要对全网开放。

---

## 9. DNS 解析

如果使用域名访问，需在域名服务商处添加 DNS 解析记录：

| 记录类型 | 主机记录 | 记录值 | TTL |
|---------|---------|--------|-----|
| A | chat（或 @） | 你的服务器公网 IP | 600 |

解析生效后即可通过 `https://chat.example.com` 访问 Web 客户端。

---

## 10. 客户端连接配置

### 10.1 Qt 桌面客户端

1. 打开客户端，在登录界面展开 **高级设置**
2. 服务器地址填写：`你的服务器公网IP`（或域名）
3. 端口填写：`9527`
4. 登录即可

### 10.2 Web 客户端

**方式一：通过 Nginx 反代（推荐）**

1. 浏览器访问 `https://chat.example.com`
2. 点击登录页的 ⚙ 服务器设置
3. 服务器地址：`chat.example.com`
4. 端口：`443`（HTTPS）或 `80`（HTTP）
5. WebSocket 路径：`/ws`

**方式二：直连 WebSocket 端口**

1. 服务器地址：`你的服务器公网IP`
2. 端口：`9528`
3. 无需填写路径

> 方式一支持 HTTPS + WSS 加密传输，推荐生产使用。

---

## 11. 部署脚本（快速部署）

项目 `deploy/` 目录下提供了 4 个脚本，可以大幅简化部署流程。前面章节是手动操作的详细说明，**如果你想快速部署，直接使用这些脚本即可**。

```
deploy/
├── build_web.bat    # [本地 Windows] 构建 Web 前端
├── upload.bat       # [本地 Windows] 上传源码到服务器
├── deploy.sh        # [服务器 Linux]  首次部署（安装依赖 + 编译 + 注册服务）
└── update.sh        # [服务器 Linux]  更新部署（重新编译 + 重启服务）
```

### 11.1 build_web.bat — 构建 Web 前端

在本地 Windows 电脑上双击运行，自动安装依赖并构建生产版本。

```powershell
# 直接双击运行，或在终端执行：
deploy\build_web.bat
```

**前置条件**：需要安装 [Node.js](https://nodejs.org/)（18+）

**脚本行为**：
1. 进入 `WebClient/` 目录
2. 执行 `npm install` 安装依赖
3. 执行 `npm run build` 构建生产版本
4. 产物输出到 `WebClient/dist/`

构建完成后，将 `dist/` 目录内的文件上传到服务器网站根目录即可。

### 11.2 upload.bat — 上传源码到服务器

通过 SCP 将服务端源码和部署脚本上传到服务器。

```powershell
# 用法
deploy\upload.bat <服务器IP> [SSH端口]

# 示例
deploy\upload.bat 47.100.1.2
deploy\upload.bat 47.100.1.2 22
```

**前置条件**：需要本地有 SSH/SCP 客户端（Windows 10+ 自带 OpenSSH）

**脚本行为**：
1. 在服务器创建 `/opt/chatroom/` 目录
2. 上传 `Server/` 和 `Common/` 源码
3. 上传 `deploy.sh` 和 `update.sh` 部署脚本

> 也可以使用宝塔面板的文件管理器手动上传，效果相同。

### 11.3 deploy.sh — 首次部署

在服务器上执行，**一键完成**环境安装、编译和服务注册。适用于全新服务器的首次部署。

```bash
# SSH 登录服务器后执行
cd /opt/chatroom
chmod +x deploy.sh
bash deploy.sh
```

**需要 root 权限执行**。脚本会自动完成以下操作：

| 步骤 | 操作 |
|------|------|
| 1 | 创建目录结构（`bin/`、`data/`、`logs/`） |
| 2 | 检查 `Server/` 和 `Common/` 源码是否存在 |
| 3 | 安装 Qt 开发库（自动尝试 Qt6 → Qt5） |
| 4 | 编译 ChatServer（release 模式） |
| 5 | 注册 `chatserver` systemd 服务（开机自启） |
| 6 | 放行防火墙端口 9527、9528 |

部署完成后的服务器目录结构：

```
/opt/chatroom/
├── deploy.sh           # 部署脚本
├── update.sh           # 更新脚本
├── Server/             # 服务端源码
├── Common/             # 公共代码
├── bin/
│   └── ChatServer      # 编译产物
├── data/
│   └── chatroom.db     # SQLite 数据库（运行后自动创建）
└── logs/
    └── chatserver.log  # 运行日志
```

### 11.4 update.sh — 更新部署

服务端代码更新后，在服务器上执行此脚本即可重新编译并重启服务。

```bash
# 1. 先上传最新的 Server/ 和 Common/（通过宝塔面板或 upload.bat）
# 2. 执行更新脚本
cd /opt/chatroom
bash update.sh
```

**需要 root 权限执行**。脚本会自动：
1. 清理旧的编译产物
2. 重新编译 ChatServer
3. 重启 `chatserver` 服务
4. 验证服务是否启动成功

### 11.5 完整快速部署流程

将以上脚本串联起来，首次部署只需 4 步：

```powershell
# ① 本地构建 Web 前端
deploy\build_web.bat

# ② 上传源码到服务器
deploy\upload.bat 你的服务器IP

# ③ SSH 登录服务器，执行部署脚本
ssh root@你的服务器IP
cd /opt/chatroom && bash deploy.sh

# ④ 通过宝塔面板上传 WebClient/dist/ 到网站根目录，配置 Nginx
```

后续更新：

```powershell
# 本地：构建 Web + 上传源码
deploy\build_web.bat
deploy\upload.bat 你的服务器IP

# 服务器：重新编译 + 重启
ssh root@你的服务器IP
cd /opt/chatroom && bash update.sh

# 通过宝塔上传新的 dist/ 文件覆盖旧文件
```

---

## 12. 运维管理

### 11.1 常用命令

```bash
# 服务管理
sudo systemctl start chatroom     # 启动
sudo systemctl stop chatroom      # 停止
sudo systemctl restart chatroom   # 重启
sudo systemctl status chatroom    # 查看状态

# 查看日志
sudo journalctl -u chatroom -f            # 实时日志
sudo journalctl -u chatroom --since today  # 今日日志

# Nginx 管理
sudo nginx -t                     # 测试配置
sudo nginx -s reload              # 重载配置
sudo nginx -s stop                # 停止
```

### 11.2 数据备份

服务端数据库文件位于 `WorkingDirectory` 下的 `chatroom.db`（SQLite），定期备份：

```bash
# 手动备份
cp /opt/chatroom/Server/chatroom.db /backup/chatroom_$(date +%Y%m%d).db

# 定时备份（crontab -e 添加）
0 3 * * * cp /opt/chatroom/Server/chatroom.db /backup/chatroom_$(date +\%Y\%m\%d).db
```

上传的文件存储在 `server_files/` 目录下，也需要备份：

```bash
# 备份上传文件
cp -r /opt/chatroom/Server/server_files/ /backup/server_files_$(date +%Y%m%d)/
```

### 11.3 更新部署

```bash
# 1. 上传新代码
scp -r Common/ Server/ root@服务器:/opt/chatroom/

# 2. 重新编译
cd /opt/chatroom/Server
make clean
qmake-qt5 Server.pro "CONFIG+=release"
make -j$(nproc)

# 3. 修改文件归属并重启服务
sudo chown -R chatroom:chatroom /opt/chatroom/
sudo systemctl restart chatroom

# 4. 更新 Web 客户端（本地构建后上传）
scp -r WebClient/dist/* root@服务器:/www/wwwroot/chat/
```

---

## 13. 常见问题排查

### Q1: 编译报错找不到 Qt 头文件

```
fatal error: QCoreApplication: No such file or directory
```

**原因**：未安装 Qt 开发包，或 qmake 版本不对。

**解决**：
```bash
# 确认已安装的 Qt 包
rpm -qa | grep qt       # CentOS
dpkg -l | grep qt       # Ubuntu

# 使用正确的 qmake
which qmake-qt5         # CentOS 通常是 qmake-qt5
which qmake6            # Ubuntu 22.04 通常是 qmake6
```

### Q2: 编译报错找不到 QWebSocket

```
Project ERROR: Unknown module(s) in QT: websockets
```

**原因**：未安装 Qt WebSocket 模块。

**解决**：
```bash
sudo apt install qt6-websockets-dev           # Ubuntu
sudo dnf install qt5-qtwebsockets-devel       # CentOS
```

### Q3: 服务启动后 Qt 客户端无法连接

**排查步骤**：
```bash
# 1. 确认服务正在运行
sudo systemctl status chatroom

# 2. 确认端口在监听
ss -tlnp | grep 9527

# 3. 检查防火墙
sudo ufw status              # Ubuntu
sudo firewall-cmd --list-all  # CentOS

# 4. 检查云安全组是否放行 9527 端口

# 5. 本地测试（在服务器上）
curl -v telnet://127.0.0.1:9527
```

### Q4: Web 客户端 WebSocket 连接失败

**排查步骤**：
```bash
# 1. 确认 WebSocket 端口在监听
ss -tlnp | grep 9528

# 2. 测试 Nginx 反代
curl -i -N \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Sec-WebSocket-Version: 13" \
  http://127.0.0.1/ws

# 3. 查看 Nginx 错误日志
tail -f /www/wwwlogs/chat.example.com.error.log
```

### Q5: HTTPS 页面无法连接 WebSocket

**原因**：HTTPS 页面只能使用 `wss://` 连接，不能使用 `ws://`。

**解决**：确保 Nginx 已配置 SSL，且 Web 客户端使用 `wss://` 协议连接。通过 Nginx 反代 `/ws` 路径即可自动升级为 WSS。

### Q6: 上传大文件失败

**原因**：Nginx 默认限制请求体大小为 1MB。

**解决**：在 Nginx 配置中添加：
```nginx
client_max_body_size 100m;   # 或更大值
```

### Q7: WebSocket 连接频繁断开

**原因**：Nginx 默认的 `proxy_read_timeout` 为 60 秒，空闲连接会被关闭。

**解决**：已在上述配置中设置 `proxy_read_timeout 86400s`。同时确认服务端心跳机制正常工作（30 秒间隔）。

### Q8: 服务器重启后数据丢失

**原因**：`chatroom.db` 和 `server_files/` 在错误的 `WorkingDirectory` 下。

**解决**：确认 systemd 服务的 `WorkingDirectory` 指向正确路径，并检查 User 权限。

---

## 架构总览

```
                         互联网
                           │
              ┌────────────┼────────────┐
              │            │            │
         ┌────▼────┐  ┌────▼────┐  ┌────▼────┐
         │ Qt 客户端│  │ 浏览器   │  │ 手机浏览器│
         │ TCP 9527│  │ HTTPS   │  │ HTTPS   │
         └────┬────┘  └────┬────┘  └────┬────┘
              │            │            │
              │       ┌────▼────────────▼────┐
              │       │      Nginx           │
              │       │  :80 → :443 重定向    │
              │       │  /    → 静态文件      │
              │       │  /ws  → 127.0.0.1:9528│
              │       └────────────┬─────────┘
              │                    │ WebSocket
              │       ┌────────────▼─────────┐
              └──────►│    ChatServer         │
                TCP   │  :9527 TCP            │
                      │  :9528 WebSocket      │
                      │  SQLite chatroom.db   │
                      │  server_files/        │
                      └──────────────────────┘
```
