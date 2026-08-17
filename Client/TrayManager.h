#pragma once

#include <QObject>

class QSystemTrayIcon;
class QMenu;
class QMainWindow;
class QAction;
class WindowsLocaleViewModel;

/// 系统托盘管理器 —— 托盘图标与通知
class TrayManager : public QObject {
    Q_OBJECT
public:
    explicit TrayManager(QMainWindow *mainWindow,
                         WindowsLocaleViewModel *localeViewModel,
                         QObject *parent = nullptr);

    bool isAvailable() const;
    void showNotification(const QString &title, const QString &message,
                          const QString &activationId = {});

signals:
    void notificationActivated(const QString &activationId);

private slots:
    void onTrayActivated(int reason);
    void refreshText();

private:
    QSystemTrayIcon *m_trayIcon = nullptr;
    QMenu           *m_trayMenu = nullptr;
    QAction         *m_showAction = nullptr;
    QAction         *m_quitAction = nullptr;
    QMainWindow     *m_mainWindow = nullptr;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    QString          m_notificationActivationId;
};
