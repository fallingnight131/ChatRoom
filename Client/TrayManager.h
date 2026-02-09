#pragma once

#include <QObject>

class QSystemTrayIcon;
class QMenu;
class QMainWindow;

/// 系统托盘管理器 —— 托盘图标与通知
class TrayManager : public QObject {
    Q_OBJECT
public:
    explicit TrayManager(QMainWindow *mainWindow, QObject *parent = nullptr);

    bool isAvailable() const;
    void showNotification(const QString &title, const QString &message);

private slots:
    void onTrayActivated(int reason);

private:
    QSystemTrayIcon *m_trayIcon = nullptr;
    QMenu           *m_trayMenu = nullptr;
    QMainWindow     *m_mainWindow = nullptr;
};
