# ChatRoom 宝塔面板部署指南

## 总览

部署分两部分：
- **服务端 (ChatServer)**：上传源码到服务器编译，注册为系统服务
- **Web 前端**：本地构建后上传 `dist` 静态文件，通过宝塔 Nginx 托管

---

## 一、准备工作

### 1.1 宝塔面板安装软件

打开宝塔面板 → **软件商店**，安装：
- ✅ **Nginx**（推荐最新版）

> Node.js 不需要在服务器安装，Web 前端在本地构建

### 1.2 阿里云安全组放行端口

阿里云控制台 → 实例 → 安全组 → 添加入方向规则：

| 端口 | 协议 | 说明 |
|------|------|------|
| 80 | TCP | HTTP（自动跳转 HTTPS） |
| 443 | TCP/UDP | HTTPS + QUIC |
| 9527 | TCP | Qt 桌面客户端 |
| 9528 | TCP | WebSocket（直连备用） |

### 1.3 宝塔面板放行端口

宝塔面板 → **安全** → **系统防火墙** → 放行端口：
- `9527`
- `9528`

---

## 二、部署服务端

### 2.1 上传文件

通过宝塔 **文件管理器**：

1. 创建目录 `/opt/chatroom`
2. 将以下文件夹上传到 `/opt/chatroom/`：
   - `Server/` （整个文件夹）
   - `Common/` （整个文件夹）
   - `deploy/deploy.sh`

上传后目录结构：
```
/opt/chatroom/
├── deploy.sh
├── Server/
│   ├── Server.pro
│   ├── main.cpp
│   ├── ChatServer.cpp
│   ├── ChatServer.h
│   ├── ...
└── Common/
    ├── Common.pri
    ├── Protocol.h
    ├── Message.cpp
    └── Message.h
```

### 2.2 执行部署脚本

宝塔面板 → **终端**（或 SSH 连接服务器），执行：

```bash
cd /opt/chatroom
chmod +x deploy.sh
bash deploy.sh
```

脚本会自动：
- 安装 Qt 开发库 + 编译工具
- 编译 ChatServer
- 注册 systemd 服务（开机自启）
- 放行防火墙端口

看到 `服务端部署完成！` 即成功。

### 2.3 验证服务

```bash
systemctl status chatserver
```

---

## 三、部署 Web 前端

### 3.1 本地构建

在你的 Windows 电脑上（需要安装 [Node.js](https://nodejs.org/)）：

**方法一：运行脚本**
```
双击 deploy\build_web.bat
```

**方法二：手动执行**
```powershell
cd WebClient
npm install
npm run build
```

构建完成后，`WebClient\dist\` 目录下会生成静态文件。

### 3.2 宝塔创建网站

宝塔面板 → **网站** → **添加站点**：

| 设置项 | 值 |
|--------|-----|
| 域名 | `fallingnight.cn` |
| 根目录 | `/www/wwwroot/chatroom/dist` |
| PHP版本 | 纯静态 |

> 如已有站点则跳过此步，直接在现有站点设置中操作

### 3.3 上传 Web 文件

通过宝塔 **文件管理器**：

1. 进入 `/www/wwwroot/chatroom/dist/`（如不存在则创建）
2. 删除里面旧文件
3. 将本地 `WebClient\dist\` 文件夹内的 **所有文件** 上传到此目录

上传后目录结构：
```
/www/wwwroot/chatroom/dist/
├── index.html
├── assets/
│   ├── index-xxx.js
│   └── index-xxx.css
└── ...
```

### 3.4 配置 Nginx

宝塔面板 → **网站** → 点击你的站点 → **设置** → **配置文件**

在 `server { }` 块内（`#API-PROXY-END` 之后、`#REWRITE-START` 之前），添加以下配置：

```nginx
    #WS-PROXY-START WebSocket反向代理
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
    }
    #WS-PROXY-END

    #VUE-ROUTER-START Vue前端路由
    location / {
        try_files $uri $uri/ /index.html;
    }
    #VUE-ROUTER-END
```

保存后点 **重启 Nginx**。

> 如果站点已配置 SSL 证书和 HTTP→HTTPS 跳转，WebSocket 会自动使用 `wss://` 加密连接

---

## 四、使用

### Web 客户端

浏览器访问：`https://fallingnight.cn`

登录页面 → 点击 **⚙ 服务器设置**：
- 服务器地址：`fallingnight.cn`
- 端口：`443`
- 勾选 ✅ **通过 Nginx 代理连接**

> 由于站点开启了 HTTPS，WebSocket 会自动使用 `wss://` 加密协议

### Qt 桌面客户端

- 服务器地址：`fallingnight.cn`（或服务器公网 IP）
- 端口：`9527`

---

## 五、常用运维命令

```bash
# 查看服务状态
systemctl status chatserver

# 重启服务
systemctl restart chatserver

# 停止服务
systemctl stop chatserver

# 查看日志
tail -f /opt/chatroom/logs/chatserver.log

# 查看数据库
ls -la /opt/chatroom/data/chatroom.db
```

---

## 六、更新代码

### 更新服务端
1. 通过宝塔上传最新的 `Server/` 和 `Common/` 到 `/opt/chatroom/`（覆盖）
2. 上传 `deploy/update.sh` 到 `/opt/chatroom/`
3. 终端执行：
```bash
cd /opt/chatroom
bash update.sh
```

### 更新 Web 前端
1. 本地重新执行 `deploy\build_web.bat`
2. 通过宝塔上传 `dist/` 内容到 `/www/wwwroot/chatroom/dist/`（覆盖）
3. 宝塔面板清除 Nginx 缓存或 Ctrl+F5 强制刷新浏览器
