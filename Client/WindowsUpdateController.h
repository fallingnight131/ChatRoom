#pragma once

#include "UpdateCheckApplicationService.h"
#include "WindowsUpdateInstallCoordinator.h"
#include "WindowsUpdateProductConfiguration.h"
#include "WindowsUpdateRuntimePaths.h"

#include <QObject>
#include <QPointer>

class QProgressDialog;
class QWidget;

class WindowsUpdateController : public QObject {
    Q_OBJECT
public:
    WindowsUpdateController(
        WindowsUpdateProductConfiguration::Value configuration,
        WindowsUpdateRuntimePaths paths,
        QObject *parent = nullptr);

    bool isEnabled() const;
    bool checkForUpdates(QWidget *owner, bool userInitiated,
                         QString *error = nullptr);

signals:
    void quitRequested();

private:
    void handleProgress(qint64 received, qint64 expected);
    void handleCheckFinished(
        UpdateCheckApplicationService::Outcome outcome,
        const UpdatePreparationApplicationService::PreparedInstaller &installer,
        const QString &targetVersion, const QString &error);
    void handleInstallFinished(
        const WindowsUpdateInstallCoordinator::Result &result);
    void closeProgress();
    void removePreparedInstaller();
    void releaseOwner();
    QWidget *messageOwner() const;

    WindowsUpdateProductConfiguration::Value m_configuration;
    WindowsUpdateRuntimePaths m_paths;
    UpdateCheckApplicationService *m_check = nullptr;
    WindowsUpdateInstallCoordinator *m_install = nullptr;
    QPointer<QWidget> m_owner;
    QMetaObject::Connection m_ownerDestroyedConnection;
    QPointer<QProgressDialog> m_progress;
    QString m_preparedInstallerPath;
    bool m_userInitiated = false;
};
