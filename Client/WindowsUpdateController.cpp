#include "WindowsUpdateController.h"

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
        WindowsUpdateRuntimePaths paths, QObject *parent)
    : QObject(parent),
      m_configuration(std::move(configuration)),
      m_paths(std::move(paths)) {
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
        m_progress = new QProgressDialog(
            QStringLiteral("正在安全检查更新…"), QStringLiteral("取消"),
            0, 0, messageOwner());
        m_progress->setWindowTitle(QStringLiteral("检查更新"));
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
        m_progress = new QProgressDialog(
            QStringLiteral("正在下载并验证安全更新…"), QStringLiteral("取消"),
            0, 1000, messageOwner());
        m_progress->setWindowTitle(QStringLiteral("安全更新"));
        m_progress->setWindowModality(Qt::WindowModal);
        m_progress->setMinimumDuration(0);
        connect(m_progress, &QProgressDialog::canceled,
                m_check, &UpdateCheckApplicationService::cancel);
        m_progress->show();
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
    using Outcome = UpdateCheckApplicationService::Outcome;
    if (outcome == Outcome::Ready) {
        m_preparedInstallerPath = installer.path;
        const auto choice = QMessageBox::question(
            messageOwner(), QStringLiteral("可安装安全更新"),
            QStringLiteral("版本 %1 已下载并通过签名验证。\n\n"
                           "现在安装会正常保存当前草稿、断开连接并重启应用。")
                .arg(targetVersion),
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
            QMessageBox::warning(messageOwner(), QStringLiteral("无法安装更新"),
                                 QStringLiteral("当前版本保持不变，请稍后重试。"));
            qWarning().noquote() << "[Updater] operation=install-start detail="
                                 << startError;
            m_userInitiated = false;
            releaseOwner();
            return;
        }
        m_progress = new QProgressDialog(
            QStringLiteral("正在准备安装并安全退出…"), QString(),
            0, 0, messageOwner());
        m_progress->setWindowTitle(QStringLiteral("准备安装"));
        m_progress->setWindowModality(Qt::ApplicationModal);
        m_progress->setCancelButton(nullptr);
        m_progress->setMinimumDuration(0);
        m_progress->show();
        return;
    }

    if (outcome == Outcome::NoUpdate && m_userInitiated) {
        QMessageBox::information(messageOwner(), QStringLiteral("检查更新"),
                                 QStringLiteral("当前已是最新版本。"));
    } else if (outcome == Outcome::ManualUpdateRequired) {
        QMessageBox::warning(messageOwner(), QStringLiteral("需要手动更新"),
                             QStringLiteral("当前版本无法自动升级到 %1，"
                                            "请从官方渠道下载新版本。")
                                 .arg(targetVersion));
    } else if (outcome == Outcome::DeferredByRollout && m_userInitiated) {
        QMessageBox::information(messageOwner(), QStringLiteral("检查更新"),
                                 QStringLiteral("新版本正在分批发布，稍后将自动可用。"));
    } else if (outcome == Outcome::Rejected) {
        qWarning().noquote() << "[Updater] operation=check outcome=rejected detail="
                             << error;
        if (m_userInitiated)
            QMessageBox::warning(messageOwner(), QStringLiteral("检查更新失败"),
                                 QStringLiteral("无法安全验证更新，未保留或安装任何更新内容。"));
    }
    m_userInitiated = false;
    releaseOwner();
}

void WindowsUpdateController::handleInstallFinished(
        const WindowsUpdateInstallCoordinator::Result &result) {
    closeProgress();
    m_userInitiated = false;
    if (!result.quitAuthorized) {
        removePreparedInstaller();
        qWarning().noquote() << "[Updater] operation=handoff outcome=failed detail="
                             << result.error;
        QMessageBox::warning(messageOwner(), QStringLiteral("更新未启动"),
                             QStringLiteral("应用将继续运行，未启动安装。请稍后重试。"));
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
