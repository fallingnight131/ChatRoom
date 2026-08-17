#include "TrayManager.h"
#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <QSystemTrayIcon>
#include <QMenu>
#include <QAction>
#include <QMainWindow>
#include <QApplication>
#include <QStyle>

#include <utility>

TrayManager::TrayManager(QMainWindow *mainWindow,
                         WindowsLocaleViewModel *localeViewModel,
                         QObject *parent)
    : QObject(parent ? parent : mainWindow)
    , m_mainWindow(mainWindow)
    , m_localeViewModel(localeViewModel)
{
    if (!QSystemTrayIcon::isSystemTrayAvailable())
        return;

    m_trayIcon = new QSystemTrayIcon(this);
    m_trayIcon->setIcon(QIcon(":/icons/app.png"));

    // 托盘菜单
    m_trayMenu = new QMenu(m_mainWindow);
    m_showAction = m_trayMenu->addAction(QString{}, [this] {
        m_mainWindow->show();
        m_mainWindow->raise();
        m_mainWindow->activateWindow();
    });
    m_trayMenu->addSeparator();
    m_quitAction = m_trayMenu->addAction(QString{}, [] {
        // 先主动断开网络连接，通知服务器用户离开
        extern void cleanupAndQuit();
        cleanupAndQuit();
    });

    m_trayIcon->setContextMenu(m_trayMenu);

    connect(m_trayIcon, &QSystemTrayIcon::activated, this, &TrayManager::onTrayActivated);
    connect(m_trayIcon, &QSystemTrayIcon::messageClicked, this, [this] {
        const QString activationId = std::exchange(
            m_notificationActivationId, QString{});
        if (!activationId.isEmpty()) emit notificationActivated(activationId);
    });
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &TrayManager::refreshText);
    }

    refreshText();
    m_trayIcon->show();
}

bool TrayManager::isAvailable() const {
    return m_trayIcon != nullptr;
}

void TrayManager::showNotification(
        const QString &title, const QString &message,
        const QString &activationId) {
    if (m_trayIcon) {
        m_notificationActivationId = activationId;
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

void TrayManager::refreshText() {
    const WindowsLocale locale = m_localeViewModel
        ? m_localeViewModel->locale() : WindowsLocaleCatalog::defaultLocale();
    const auto &copy = WindowsLocaleCatalog::messages(locale);
    if (m_trayIcon) m_trayIcon->setToolTip(copy.trayApplicationName);
    if (m_showAction) m_showAction->setText(copy.trayShowMainWindow);
    if (m_quitAction) m_quitAction->setText(copy.trayQuit);
}
