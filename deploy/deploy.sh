#!/bin/bash
#======================================================================
# ChatRoom 服务端部署脚本 (宝塔面板版)
#
# 适用于: Alibaba Cloud Linux 3 / CentOS 8 / RHEL 8 + 宝塔面板
#
# 功能:
#   1. 安装 Qt 开发环境
#   2. 编译 ChatServer
#   3. 注册 systemd 服务 (开机自启)
#
# Web 前端由宝塔面板管理 (本地构建后上传 dist 文件夹即可)
#
# 用法:
#   将 Server、Common 文件夹 和 deploy/deploy.sh 上传到服务器 /opt/chatroom/
#   cd /opt/chatroom
#   chmod +x deploy.sh
#   bash deploy.sh
#
# 目录结构 (上传后):
#   /opt/chatroom/
#   ├── deploy.sh        ← 本脚本
#   ├── Server/          ← 服务端源码
#   ├── Common/          ← 公共代码
#   ├── bin/             ← (自动创建) 编译产物
#   ├── data/            ← (自动创建) 数据库文件
#   └── logs/            ← (自动创建) 日志
#======================================================================

set -e

PROJECT_DIR="/opt/chatroom"
BIN_DIR="${PROJECT_DIR}/bin"
DATA_DIR="${PROJECT_DIR}/data"
LOG_DIR="${PROJECT_DIR}/logs"

TCP_PORT=9527
WS_PORT=9528

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[✓]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
log_error() { echo -e "${RED}[✗]${NC} $1"; }

if [ "$(id -u)" -ne 0 ]; then
    log_error "请使用 root 用户执行: bash deploy.sh"
    exit 1
fi

echo ""
echo "======================================"
echo "  ChatRoom 服务端部署 (宝塔面板版)"
echo "======================================"
echo ""

# ==================== 1. 创建目录 ====================
mkdir -p "${BIN_DIR}" "${DATA_DIR}" "${LOG_DIR}"

# ==================== 2. 检查源码 ====================
if [ ! -f "${PROJECT_DIR}/Server/Server.pro" ]; then
    log_error "未找到 Server/Server.pro"
    log_error "请将 Server/ 和 Common/ 文件夹上传到 ${PROJECT_DIR}/"
    exit 1
fi
if [ ! -d "${PROJECT_DIR}/Common" ]; then
    log_error "未找到 Common/ 目录"
    exit 1
fi
log_info "源码检查通过"

# ==================== 3. 安装 Qt 依赖 ====================
log_info "安装编译依赖..."

dnf install -y gcc gcc-c++ make 2>/dev/null || yum install -y gcc gcc-c++ make

# 尝试安装 Qt6
QT_INSTALLED=false
for try_cmd in \
    "dnf install -y qt6-qtbase-devel qt6-qtwebsockets-devel" \
    "dnf install -y --enablerepo=powertools qt6-qtbase-devel qt6-qtwebsockets-devel" \
    "dnf install -y --enablerepo=crb qt6-qtbase-devel qt6-qtwebsockets-devel" \
    "dnf install -y qt5-qtbase-devel qt5-qtwebsockets-devel" \
    "yum install -y qt5-qtbase-devel qt5-qtwebsockets-devel"
do
    if eval "$try_cmd" 2>/dev/null; then
        QT_INSTALLED=true
        break
    fi
done

if ! $QT_INSTALLED; then
    log_error "Qt 安装失败，请手动安装 Qt 开发库"
    exit 1
fi

# 安装 SQLite
dnf install -y sqlite-devel 2>/dev/null || yum install -y sqlite-devel 2>/dev/null || true

# 找到 qmake
QMAKE=""
for cmd in qmake6 qmake-qt6 qmake-qt5 qmake; do
    if command -v "$cmd" &>/dev/null; then
        QMAKE="$cmd"
        break
    fi
done

if [ -z "$QMAKE" ]; then
    log_error "未找到 qmake 命令"
    exit 1
fi
log_info "使用 $QMAKE"

# ==================== 4. 编译服务端 ====================
log_info "编译 ChatServer..."

cd "${PROJECT_DIR}/Server"
rm -rf build && mkdir build && cd build
${QMAKE} ../Server.pro CONFIG+=release -o Makefile
make -j$(nproc)

cp ChatServer "${BIN_DIR}/"
chmod +x "${BIN_DIR}/ChatServer"
log_info "编译完成: ${BIN_DIR}/ChatServer"

# ==================== 5. 注册 systemd 服务 ====================
log_info "注册系统服务..."

cat > /etc/systemd/system/chatserver.service << EOF
[Unit]
Description=ChatRoom Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${BIN_DIR}
Environment=CHATROOM_DB_PATH=${DATA_DIR}/chatroom.db
ExecStart=${BIN_DIR}/ChatServer --port ${TCP_PORT} --ws-port ${WS_PORT}
Restart=always
RestartSec=5
StandardOutput=append:${LOG_DIR}/chatserver.log
StandardError=append:${LOG_DIR}/chatserver.log
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable chatserver
systemctl restart chatserver

sleep 2
if systemctl is-active --quiet chatserver; then
    log_info "ChatServer 服务启动成功 ✓"
else
    log_error "服务启动失败，查看日志: tail -f ${LOG_DIR}/chatserver.log"
    tail -20 "${LOG_DIR}/chatserver.log" 2>/dev/null
    exit 1
fi

# ==================== 6. 防火墙 ====================
if command -v firewall-cmd &>/dev/null; then
    firewall-cmd --permanent --add-port=${TCP_PORT}/tcp 2>/dev/null || true
    firewall-cmd --permanent --add-port=${WS_PORT}/tcp 2>/dev/null || true
    firewall-cmd --reload 2>/dev/null || true
    log_info "防火墙已放行 ${TCP_PORT}, ${WS_PORT}"
fi

# ==================== 完成 ====================
PUBLIC_IP=$(curl -s --max-time 5 http://ifconfig.me 2>/dev/null || echo "<你的服务器IP>")

echo ""
echo "======================================"
echo "  服务端部署完成！"
echo "======================================"
echo ""
log_info "TCP  端口: ${TCP_PORT} (Qt桌面客户端)"
log_info "WS   端口: ${WS_PORT} (WebSocket)"
log_info "数据库:    ${DATA_DIR}/chatroom.db"
log_info "日志:      ${LOG_DIR}/chatserver.log"
echo ""
echo "常用命令:"
echo "  systemctl status chatserver     # 查看状态"
echo "  systemctl restart chatserver    # 重启"
echo "  systemctl stop chatserver       # 停止"
echo "  tail -f ${LOG_DIR}/chatserver.log  # 查看日志"
echo ""
echo "========================================"
echo "  接下来请在宝塔面板中完成 Web 端部署"
echo "========================================"
echo ""
echo "步骤 1: 宝塔面板 → 安全 → 放行端口 ${WS_PORT}"
echo "步骤 2: 宝塔面板 → 网站 → 添加站点 → 配置 Nginx"
echo "步骤 3: 阿里云安全组放行 80, ${TCP_PORT}, ${WS_PORT}"
echo ""
echo "详细操作见 deploy/README_DEPLOY.md"
echo ""