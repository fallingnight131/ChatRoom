#pragma once

#include <QDialog>

class DeviceManagementViewModel;
class QLabel;
class QListWidget;
class QPushButton;

class DeviceManagementDialog final : public QDialog {
    Q_OBJECT
public:
    explicit DeviceManagementDialog(DeviceManagementViewModel *viewModel,
                                    QWidget *parent = nullptr);
private:
    void render();
    void requestRevoke(const QString &deviceId);
    DeviceManagementViewModel *m_viewModel;
    QLabel *m_status;
    QListWidget *m_devices;
    QPushButton *m_refresh;
};
