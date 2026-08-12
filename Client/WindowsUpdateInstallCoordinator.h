#pragma once

#include "UpdateLifecycleRepository.h"
#include "WindowsUpdateHandoffApplicationService.h"

#include <QObject>

class WindowsUpdateInstallCoordinator : public QObject {
    Q_OBJECT
public:
    struct Request {
        WindowsUpdateHandoffApplicationService::Request handoff;
        QString targetVersion;
        QDateTime createdAtUtc;
    };

    struct Result {
        bool quitAuthorized = false;
        QString requestId;
        QString error;
    };

    WindowsUpdateInstallCoordinator(
        QString lifecycleStateDirectory,
        QString resultDirectory,
        QString runRootDirectory,
        QObject *parent = nullptr);
    WindowsUpdateInstallCoordinator(
        QString lifecycleStateDirectory,
        QString resultDirectory,
        QString runRootDirectory,
        WindowsUpdateHandoffApplicationService::LaunchHandshakeFunction handshake,
        QObject *parent = nullptr);

    bool start(const Request &request, QString *error = nullptr);
    bool isActive() const;

signals:
    void finished(const WindowsUpdateInstallCoordinator::Result &result);

private:
    void handleHandoff(
        const WindowsUpdateHandoffApplicationService::Result &handoff);

    UpdateLifecycleRepository m_lifecycle;
    WindowsUpdateHandoffApplicationService *m_handoff = nullptr;
    Request m_request;
};

Q_DECLARE_METATYPE(WindowsUpdateInstallCoordinator::Result)
