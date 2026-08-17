#pragma once

#include "UpdateCheckApplicationService.h"
#include "WindowsUpdateInstallCoordinator.h"
#include "WindowsUpdateProductConfiguration.h"
#include "WindowsUpdateRuntimePaths.h"

#include <QObject>
#include <QPointer>

class QProgressDialog;
class QWidget;
class WindowsLocaleViewModel;

class WindowsUpdateController : public QObject {
    Q_OBJECT
public:
    WindowsUpdateController(
        WindowsUpdateProductConfiguration::Value configuration,
        WindowsUpdateRuntimePaths paths,
        WindowsLocaleViewModel *localeViewModel,
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
    void refreshProgressCopy();
    QWidget *messageOwner() const;

    enum class ProgressKind { None, Checking, Downloading, Preparing };

    WindowsUpdateProductConfiguration::Value m_configuration;
    WindowsUpdateRuntimePaths m_paths;
    UpdateCheckApplicationService *m_check = nullptr;
    WindowsUpdateInstallCoordinator *m_install = nullptr;
    QPointer<QWidget> m_owner;
    QMetaObject::Connection m_ownerDestroyedConnection;
    QPointer<QProgressDialog> m_progress;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    ProgressKind m_progressKind = ProgressKind::None;
    QString m_preparedInstallerPath;
    bool m_userInitiated = false;
};
