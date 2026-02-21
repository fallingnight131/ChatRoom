@echo off
chcp 65001 >nul
REM ======================================================
REM 本地构建 Web 前端
REM 构建完成后，将 WebClient\dist 文件夹上传到宝塔即可
REM ======================================================

setlocal
cd /d "%~dp0..\WebClient"

echo.
echo ========================================
echo   构建 Web 前端
echo ========================================
echo.

REM 检查 Node.js
where node >nul 2>nul
if errorlevel 1 (
    echo [错误] 未安装 Node.js，请先安装: https://nodejs.org/
    pause
    exit /b 1
)

echo [1/2] 安装依赖...
call npm install

echo [2/2] 构建生产版本...
call npm run build

echo.
if exist dist\index.html (
    echo ========================================
    echo   构建成功！
    echo ========================================
    echo.
    echo 产物目录: %cd%\dist
    echo.
    echo 下一步：
    echo   1. 打开宝塔面板
    echo   2. 将 dist 文件夹内的所有文件上传到网站根目录
    echo.
) else (
    echo [错误] 构建失败，请检查错误信息
)
pause
