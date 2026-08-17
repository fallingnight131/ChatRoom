#pragma once

#include <QDialog>
#include "WindowsLocaleCatalog.h"

class DeviceManagementViewModel;
class QLabel;
class QListWidget;
class QPushButton;
class WindowsLocaleViewModel;

class DeviceManagementDialog final : public QDialog {
    Q_OBJECT
public:
    explicit DeviceManagementDialog(DeviceManagementViewModel *viewModel,
                                    QWidget *parent = nullptr,
                                    WindowsLocaleViewModel *localeViewModel = nullptr);
    QLabel *statusForTest() const { return m_status; }
    QListWidget *devicesForTest() const { return m_devices; }
    QPushButton *refreshForTest() const { return m_refresh; }
    QPushButton *closeForTest() const { return m_close; }
private:
    void applyLocale();
    void render();
    QString failureText() const;
    void requestRevoke(const QString &deviceId);
    DeviceManagementViewModel *m_viewModel;
    QLabel *m_status;
    QLabel *m_intro;
    QListWidget *m_devices;
    QPushButton *m_refresh;
    QPushButton *m_close;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
};
