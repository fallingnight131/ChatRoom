@echo off
chcp 65001 >nul
REM ======================================================
REM SCP 上传到服务器 (备选方案，推荐用宝塔面板直接上传)
REM
REM 用法: upload.bat <服务器IP> [SSH端口]
REM 示例: upload.bat 47.100.1.2
REM ======================================================

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo 用法: upload.bat ^<服务器IP^> [SSH端口]
    echo.
    echo 推荐使用宝塔面板的文件管理器直接上传，更简单！
    echo 详见 deploy\README_DEPLOY.md
    pause
    exit /b 1
)

set SERVER_IP=%~1
set SSH_PORT=%~2
if "%SSH_PORT%"=="" set SSH_PORT=22
set REMOTE_DIR=/opt/chatroom
set PROJECT_ROOT=%~dp0..

echo ==========================================
echo   上传 ChatRoom 到 %SERVER_IP%
echo ==========================================
echo.

echo [1/3] 创建远端目录...
ssh -p %SSH_PORT% root@%SERVER_IP% "mkdir -p %REMOTE_DIR%"

echo [2/3] 上传服务端源码...
scp -P %SSH_PORT% -r "%PROJECT_ROOT%\Server" root@%SERVER_IP%:%REMOTE_DIR%/
scp -P %SSH_PORT% -r "%PROJECT_ROOT%\Common" root@%SERVER_IP%:%REMOTE_DIR%/

echo [3/3] 上传部署脚本...
scp -P %SSH_PORT% "%PROJECT_ROOT%\deploy\deploy.sh" root@%SERVER_IP%:%REMOTE_DIR%/
scp -P %SSH_PORT% "%PROJECT_ROOT%\deploy\update.sh" root@%SERVER_IP%:%REMOTE_DIR%/

echo.
echo ==========================================
echo   上传完成！
echo ==========================================
echo.
echo 下一步 SSH 登录执行:
echo   ssh -p %SSH_PORT% root@%SERVER_IP%
echo   cd %REMOTE_DIR%
echo   chmod +x deploy.sh
echo   bash deploy.sh
echo.
pause
