#pragma once

#include "V2WindowsDeviceManagementTransport.h"
#include <QObject>
#include <QUrl>
#include <memory>

class DeviceManagementApplicationService;
class DeviceManagementViewModel;

class WindowsDeviceManagementController final : public QObject {
public:
    WindowsDeviceManagementController(
        QUrl endpoint,
        QString appVersion,
        QString deviceId,
        QString username,
        QByteArray passwordUtf8,
        QWebSocket *socket = nullptr,
        V2WindowsDeviceManagementTransport::SocketHooks hooks = {},
        QObject *parent = nullptr);
    ~WindowsDeviceManagementController() override;

    DeviceManagementViewModel *viewModel() const;
    bool start();
    void stop();

private:
    std::unique_ptr<V2WindowsDeviceManagementTransport> m_transport;
    std::unique_ptr<DeviceManagementViewModel> m_viewModel;
    std::unique_ptr<DeviceManagementApplicationService> m_service;
};
