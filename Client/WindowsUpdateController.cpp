#include "WindowsUpdateController.h"
#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QMessageBox>
#include <QProgressDialog>
#include <QtGlobal>

#include <utility>

WindowsUpdateController::WindowsUpdateController(
        WindowsUpdateProductConfiguration::Value configuration,
        WindowsUpdateRuntimePaths paths,
        WindowsLocaleViewModel *localeViewModel, QObject *parent)
    : QObject(parent),
      m_configuration(std::move(configuration)),
      m_paths(std::move(paths)),
      m_localeViewModel(localeViewModel) {
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed, this,
                &WindowsUpdateController::refreshProgressCopy);
    }
    if (!m_configuration.enabled) return;
    m_check = new UpdateCheckApplicationService(
        m_configuration.trustedKeys, m_paths.manifestStateDirectory,
        m_paths.stagingDirectory, this);
    m_install = new WindowsUpdateInstallCoordinator(
        m_paths.lifecycleStateDirectory, m_paths.resultDirectory,
        m_paths.runRootDirectory, this);
    connect(m_check, &UpdateCheckApplicationService::progress, this,
            &WindowsUpdateController::handleProgress);
    connect(m_check, &UpdateCheckApplicationService::finished, this,
            &WindowsUpdateController::handleCheckFinished);
    connect(m_install, &WindowsUpdateInstallCoordinator::finished, this,
            &WindowsUpdateController::handleInstallFinished);
}

bool WindowsUpdateController::isEnabled() const {
    return m_configuration.enabled && m_check && m_install;
}

bool WindowsUpdateController::checkForUpdates(
        QWidget *owner, bool userInitiated, QString *error) {
    if (error) error->clear();
    if (!isEnabled()) {
        if (error) *error = m_configuration.error.isEmpty()
            ? QStringLiteral("Windows update channel is disabled")
            : m_configuration.error;
        return false;
    }
    if (m_check->isActive() || m_install->isActive()) {
        if (error) *error = QStringLiteral("Windows update operation is already active");
        return false;
    }
    if (m_ownerDestroyedConnection)
        disconnect(m_ownerDestroyedConnection);
    m_owner = owner;
    if (owner) {
        m_ownerDestroyedConnection = connect(
            owner, &QObject::destroyed, this, [this] {
                if (m_check && m_check->isActive()) m_check->cancel();
                closeProgress();
                m_owner.clear();
            });
    }
    m_userInitiated = userInitiated;
    const UpdateCheckApplicationService::Request request{
        m_configuration.manifestUrl,
        m_configuration.signatureUrl,
        QCoreApplication::applicationVersion(),
        m_configuration.channel,
        QDateTime::currentDateTimeUtc()
    };
    if (!m_check->start(request, error)) {
        releaseOwner();
        m_userInitiated = false;
        return false;
    }
    if (m_userInitiated) {
        m_progress = new QProgressDialog(QString(), QString(), 0, 0, messageOwner());
        m_progressKind = ProgressKind::Checking;
        refreshProgressCopy();
        m_progress->setWindowModality(Qt::WindowModal);
        m_progress->setMinimumDuration(0);
        connect(m_progress, &QProgressDialog::canceled,
                m_check, &UpdateCheckApplicationService::cancel);
        m_progress->show();
    }
    return true;
}

void WindowsUpdateController::handleProgress(qint64 received, qint64 expected) {
    if (!m_progress) {
        m_progress = new QProgressDialog(QString(), QString(), 0, 1000,
                                         messageOwner());
        m_progressKind = ProgressKind::Downloading;
        refreshProgressCopy();
        m_progress->setWindowModality(Qt::WindowModal);
        m_progress->setMinimumDuration(0);
        connect(m_progress, &QProgressDialog::canceled,
                m_check, &UpdateCheckApplicationService::cancel);
        m_progress->show();
    }
    if (m_progressKind != ProgressKind::Downloading) {
        m_progressKind = ProgressKind::Downloading;
        refreshProgressCopy();
    }
    if (expected <= 0) return;
    m_progress->setRange(0, 1000);
    m_progress->setValue(static_cast<int>(qMin<qint64>(
        1000, (received * 1000) / expected)));
}

void WindowsUpdateController::handleCheckFinished(
        UpdateCheckApplicationService::Outcome outcome,
        const UpdatePreparationApplicationService::PreparedInstaller &installer,
        const QString &targetVersion, const QString &error) {
    closeProgress();
    const auto &copy = WindowsLocaleCatalog::messages(
        m_localeViewModel ? m_localeViewModel->locale() : WindowsLocale::ZhCn);
    using Outcome = UpdateCheckApplicationService::Outcome;
    if (outcome == Outcome::Ready) {
        m_preparedInstallerPath = installer.path;
        const auto choice = QMessageBox::question(
            messageOwner(), copy.updateReadyTitle,
            copy.updateReadyBody.arg(targetVersion),
            QMessageBox::Yes | QMessageBox::No, QMessageBox::No);
        if (choice != QMessageBox::Yes) {
            removePreparedInstaller();
            m_userInitiated = false;
            releaseOwner();
            return;
        }
        const QString applicationDirectory = QCoreApplication::applicationDirPath();
        WindowsUpdateInstallCoordinator::Request request;
        request.handoff.installer = installer;
        request.handoff.installedLauncherPath = QDir(applicationDirectory).filePath(
            QStringLiteral("ChatRoomUpdateLauncher.exe"));
        request.handoff.qtCoreRuntimePath = QDir(applicationDirectory).filePath(
            QStringLiteral("Qt%1Core.dll").arg(QT_VERSION_MAJOR));
        request.handoff.restartExecutablePath = QCoreApplication::applicationFilePath();
        request.handoff.runRootDirectory = m_paths.runRootDirectory;
        request.handoff.resultDirectory = m_paths.resultDirectory;
        request.targetVersion = targetVersion;
        request.createdAtUtc = QDateTime::currentDateTimeUtc();
        QString startError;
        if (!m_install->start(request, &startError)) {
            removePreparedInstaller();
            QMessageBox::warning(messageOwner(), copy.updateInstallFailedTitle,
                                 copy.updateInstallFailedBody);
            qWarning().noquote() << "[Updater] operation=install-start detail="
                                 << startError;
            m_userInitiated = false;
            releaseOwner();
            return;
        }
        m_progress = new QProgressDialog(QString(), QString(), 0, 0,
                                         messageOwner());
        m_progressKind = ProgressKind::Preparing;
        refreshProgressCopy();
        m_progress->setWindowModality(Qt::ApplicationModal);
        m_progress->setCancelButton(nullptr);
        m_progress->setMinimumDuration(0);
        m_progress->show();
        return;
    }

    if (outcome == Outcome::NoUpdate && m_userInitiated) {
        QMessageBox::information(messageOwner(), copy.updateCheckTitle,
                                 copy.updateCurrent);
    } else if (outcome == Outcome::ManualUpdateRequired) {
        QMessageBox::warning(messageOwner(), copy.updateManualRequiredTitle,
                             copy.updateManualRequiredBody.arg(targetVersion));
    } else if (outcome == Outcome::DeferredByRollout && m_userInitiated) {
        QMessageBox::information(messageOwner(), copy.updateCheckTitle,
                                 copy.updateDeferred);
    } else if (outcome == Outcome::Rejected) {
        qWarning().noquote() << "[Updater] operation=check outcome=rejected detail="
                             << error;
        if (m_userInitiated)
            QMessageBox::warning(messageOwner(), copy.updateCheckFailedTitle,
                                 copy.updateCheckFailedBody);
    }
    m_userInitiated = false;
    releaseOwner();
}

void WindowsUpdateController::handleInstallFinished(
        const WindowsUpdateInstallCoordinator::Result &result) {
    closeProgress();
    m_userInitiated = false;
    const auto &copy = WindowsLocaleCatalog::messages(
        m_localeViewModel ? m_localeViewModel->locale() : WindowsLocale::ZhCn);
    if (!result.quitAuthorized) {
        removePreparedInstaller();
        qWarning().noquote() << "[Updater] operation=handoff outcome=failed detail="
                             << result.error;
        QMessageBox::warning(messageOwner(), copy.updateNotStartedTitle,
                             copy.updateNotStartedBody);
        releaseOwner();
        return;
    }
    m_preparedInstallerPath.clear();
    releaseOwner();
    emit quitRequested();
}

void WindowsUpdateController::closeProgress() {
    if (!m_progress) return;
    m_progress->close();
    m_progress->deleteLater();
    m_progress.clear();
    m_progressKind = ProgressKind::None;
}

void WindowsUpdateController::refreshProgressCopy() {
    if (!m_progress) return;
    const auto &copy = WindowsLocaleCatalog::messages(
        m_localeViewModel ? m_localeViewModel->locale() : WindowsLocale::ZhCn);
    switch (m_progressKind) {
    case ProgressKind::Checking:
        m_progress->setLabelText(copy.updateCheckingProgress);
        m_progress->setCancelButtonText(copy.updateCancel);
        m_progress->setWindowTitle(copy.updateCheckTitle);
        break;
    case ProgressKind::Downloading:
        m_progress->setLabelText(copy.updateDownloadingProgress);
        m_progress->setCancelButtonText(copy.updateCancel);
        m_progress->setWindowTitle(copy.updateSecurityTitle);
        break;
    case ProgressKind::Preparing:
        m_progress->setLabelText(copy.updatePreparingProgress);
        m_progress->setWindowTitle(copy.updatePreparingTitle);
        break;
    case ProgressKind::None:
        break;
    }
}

void WindowsUpdateController::removePreparedInstaller() {
    if (!m_preparedInstallerPath.isEmpty()
            && !QFile::remove(m_preparedInstallerPath)) {
        qWarning().noquote() << "[Updater] operation=cleanup path="
                             << m_preparedInstallerPath;
    }
    m_preparedInstallerPath.clear();
}

void WindowsUpdateController::releaseOwner() {
    if (m_ownerDestroyedConnection) {
        disconnect(m_ownerDestroyedConnection);
        m_ownerDestroyedConnection = {};
    }
    m_owner.clear();
}

QWidget *WindowsUpdateController::messageOwner() const {
    return m_owner.data();
}
