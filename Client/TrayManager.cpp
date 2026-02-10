#include "TrayManager.h"

#include <QSystemTrayIcon>
#include <QMenu>
#include <QAction>
#include <QMainWindow>
#include <QApplication>
#include <QStyle>

TrayManager::TrayManager(QMainWindow *mainWindow, QObject *parent)
    : QObject(parent ? parent : mainWindow)
    , m_mainWindow(mainWindow)
{
    if (!QSystemTrayIcon::isSystemTrayAvailable())
        return;

    m_trayIcon = new QSystemTrayIcon(this);
    m_trayIcon->setIcon(QIcon(":/icons/app.png"));
    m_trayIcon->setToolTip("Qt聊天室");

    // 托盘菜单
    m_trayMenu = new QMenu;
    m_trayMenu->addAction("显示主窗口", [this] {
        m_mainWindow->show();
        m_mainWindow->raise();
        m_mainWindow->activateWindow();
    });
    m_trayMenu->addSeparator();
    m_trayMenu->addAction("退出", [this] {
        // 先主动断开网络连接，通知服务器用户离开
        extern void cleanupAndQuit();
        cleanupAndQuit();
    });

    m_trayIcon->setContextMenu(m_trayMenu);

    connect(m_trayIcon, &QSystemTrayIcon::activated, this, &TrayManager::onTrayActivated);

    m_trayIcon->show();
}

bool TrayManager::isAvailable() const {
    return m_trayIcon != nullptr;
}

void TrayManager::showNotification(const QString &title, const QString &message) {
    if (m_trayIcon) {
        m_trayIcon->showMessage(title, message, QSystemTrayIcon::Information, 3000);
    }
}

void TrayManager::onTrayActivated(int reason) {
    if (reason == QSystemTrayIcon::DoubleClick) {
        if (m_mainWindow->isVisible()) {
            m_mainWindow->hide();
        } else {
            m_mainWindow->show();
            m_mainWindow->raise();
            m_mainWindow->activateWindow();
        }
    }
}
