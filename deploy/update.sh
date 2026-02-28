#!/bin/bash
#======================================================================
# ChatRoom 服务端更新脚本 (宝塔面板版)
#
# 更新 Server 和 Common 源码后重新编译
# Web 前端请在本地重新构建后通过宝塔上传
#
# 用法:
#   上传最新的 Server/ 和 Common/ 到 /opt/chatroom/
#   bash update.sh
#======================================================================

set -e

PROJECT_DIR="/opt/chatroom"
BIN_DIR="${PROJECT_DIR}/bin"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[✓]${NC} $1"; }
log_error() { echo -e "${RED}[✗]${NC} $1"; }

if [ "$(id -u)" -ne 0 ]; then
    log_error "请使用 root 用户执行"
    exit 1
fi

# 找 qmake
QMAKE=""
for cmd in qmake6 qmake-qt6 qmake-qt5 qmake; do
    if command -v "$cmd" &>/dev/null; then
        QMAKE="$cmd"
        break
    fi
done

if [ -z "$QMAKE" ]; then
    log_error "未找到 qmake"
    exit 1
fi

# 确保目录存在
mkdir -p "${BIN_DIR}" "${PROJECT_DIR}/data" "${PROJECT_DIR}/logs"

# 重新编译
log_info "重新编译 ChatServer..."
cd "${PROJECT_DIR}/Server"
rm -rf build && mkdir build && cd build
${QMAKE} ../Server.pro CONFIG+=release -o Makefile
make -j$(nproc)

# 先停止服务再复制（避免"文本文件忙"错误）
log_info "停止服务..."
systemctl stop chatserver 2>/dev/null || true
sleep 1

cp ChatServer "${BIN_DIR}/"
chmod +x "${BIN_DIR}/ChatServer"

# 启动服务
log_info "启动服务..."
systemctl start chatserver

sleep 2
if systemctl is-active --quiet chatserver; then
    log_info "更新完成，服务已重启 ✓"
else
    log_error "服务启动失败"
    tail -20 /opt/chatroom/logs/chatserver.log 2>/dev/null
fi
