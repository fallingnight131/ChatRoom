@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ======================================================================
REM  ChatRoom 本地开发脚本 (Qt客户端)
REM  功能: 编译服务端和Qt客户端 → 清空数据库和文件 → 启动1服务端+2客户端
REM ======================================================================

set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

REM --- Qt / MinGW 路径 (请根据实际安装路径修改) ---
set "QT_DIR=D:\ProgramFiles\QT\6.7.2\mingw_64"
set "MINGW_DIR=D:\ProgramFiles\QT\Tools\mingw1120_64"
set "QMAKE=%QT_DIR%\bin\qmake.exe"
set "MAKE=%MINGW_DIR%\bin\mingw32-make.exe"

REM 加入 PATH
set "PATH=%QT_DIR%\bin;%MINGW_DIR%\bin;%PATH%"

echo ============================================================
echo   ChatRoom 本地开发 (Qt客户端模式)
echo ============================================================
echo.

REM --- 1. 杀掉可能残留的旧进程 ---
echo [1/5] 清理旧进程...
taskkill /f /im ChatServer.exe >nul 2>&1
taskkill /f /im ChatClient.exe >nul 2>&1
timeout /t 1 /nobreak >nul
echo [OK] 旧进程已清理
echo.

REM --- 2. 编译服务端 ---
echo [2/5] 编译服务端...
cd /d "%PROJECT_DIR%Server"
"%QMAKE%" Server.pro "CONFIG+=release" -o Makefile
if errorlevel 1 (
    echo [错误] qmake 服务端失败
    pause
    exit /b 1
)
"%MAKE%" -f Makefile.Release -j%NUMBER_OF_PROCESSORS%
if errorlevel 1 (
    echo [错误] 编译服务端失败
    pause
    exit /b 1
)
echo [OK] 服务端编译成功
echo.

REM --- 3. 编译客户端 ---
echo [3/5] 编译客户端...
cd /d "%PROJECT_DIR%Client"
"%QMAKE%" Client.pro "CONFIG+=release" -o Makefile
if errorlevel 1 (
    echo [错误] qmake 客户端失败
    pause
    exit /b 1
)
"%MAKE%" -f Makefile.Release -j%NUMBER_OF_PROCESSORS%
if errorlevel 1 (
    echo [错误] 编译客户端失败
    pause
    exit /b 1
)
echo [OK] 客户端编译成功
echo.

REM --- 4. 清空数据库和 server_files ---
echo [4/5] 清空数据库和文件...
cd /d "%PROJECT_DIR%Server\release"
if exist chatroom.db del /q chatroom.db
if exist server_files rd /s /q server_files
echo [OK] 数据已清空
echo.

REM --- 5. 启动 1 服务端 + 2 客户端 ---
echo [5/5] 启动应用...
echo   启动服务端...
cd /d "%PROJECT_DIR%Server\release"
start "ChatServer" ChatServer.exe

timeout /t 2 /nobreak >nul

echo   启动客户端 1...
cd /d "%PROJECT_DIR%Client\release"
start "ChatClient-1" ChatClient.exe

timeout /t 1 /nobreak >nul

echo   启动客户端 2...
start "ChatClient-2" ChatClient.exe

echo.
echo ============================================================
echo   全部启动完成!
echo   - 服务端: Server\release\ChatServer.exe
echo   - 客户端1 和 客户端2: Client\release\ChatClient.exe
echo ============================================================
echo.
echo 按任意键关闭所有进程并退出...
pause >nul

echo 正在关闭所有进程...
taskkill /f /im ChatServer.exe >nul 2>&1
taskkill /f /im ChatClient.exe >nul 2>&1
echo 已退出
