#include "WindowsUpdateHandoffApplicationService.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QProcess>
#include <QUuid>
#include <QtConcurrent/QtConcurrentRun>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

#include <utility>

namespace {
constexpr int HandshakeTimeoutMs = 15 * 1000;

bool safeFile(const QString &path, const QString &expectedName) {
    const QFileInfo info(path);
    return info.isAbsolute() && info.exists() && info.isFile() && !info.isSymLink()
        && info.fileName().compare(expectedName, Qt::CaseInsensitive) == 0;
}

bool safeRegularFile(const QString &path) {
    const QFileInfo info(path);
    return info.isAbsolute() && info.exists() && info.isFile() && !info.isSymLink();
}

bool safeDirectory(const QString &path) {
    const QFileInfo info(path);
    return info.isAbsolute() && info.exists() && info.isDir() && !info.isSymLink();
}

bool cleanupRunDirectory(const QString &path) {
    return path.isEmpty() || QDir(path).removeRecursively();
}
}

WindowsUpdateHandoffApplicationService::WindowsUpdateHandoffApplicationService(
        QObject *parent)
    : WindowsUpdateHandoffApplicationService(&launchAndHandshake, parent) {}

WindowsUpdateHandoffApplicationService::WindowsUpdateHandoffApplicationService(
        LaunchHandshakeFunction launchHandshake, QObject *parent)
    : QObject(parent), m_watcher(new QFutureWatcher<Result>(this)),
      m_launchHandshake(std::move(launchHandshake)) {
    connect(m_watcher, &QFutureWatcherBase::finished, this,
            &WindowsUpdateHandoffApplicationService::handleFinished);
}

WindowsUpdateHandoffApplicationService::~WindowsUpdateHandoffApplicationService() {
    disconnect(m_watcher, nullptr, this, nullptr);
    if (m_watcher->isRunning()) m_watcher->waitForFinished();
}

bool WindowsUpdateHandoffApplicationService::start(
        const Request &request, QString *error) {
    if (error) error->clear();
    if (isActive() || !m_launchHandshake) {
        if (error) *error = QStringLiteral("Windows update handoff is unavailable or active");
        return false;
    }
    m_watcher->setFuture(QtConcurrent::run(
        [request, launchHandshake = m_launchHandshake]() {
            return execute(request, launchHandshake);
        }));
    return true;
}

bool WindowsUpdateHandoffApplicationService::isActive() const {
    return m_watcher && m_watcher->isRunning();
}

WindowsUpdateHandoffApplicationService::Result
WindowsUpdateHandoffApplicationService::execute(
        const Request &request,
        const LaunchHandshakeFunction &launchHandshake) {
    Result result;
    const QFileInfo launcherInfo(request.installedLauncherPath);
    const QFileInfo coreInfo(request.qtCoreRuntimePath);
    const QFileInfo restartInfo(request.restartExecutablePath);
    if (!request.installer.isComplete()
            || !safeRegularFile(request.installer.path)
            || QFileInfo(request.installer.path).suffix().compare(
                QStringLiteral("exe"), Qt::CaseInsensitive) != 0
            || !safeFile(request.installedLauncherPath,
                         QStringLiteral("ChatRoomUpdateLauncher.exe"))
            || !safeFile(request.qtCoreRuntimePath, QStringLiteral("Qt6Core.dll"))
            || !safeFile(request.restartExecutablePath, QStringLiteral("ChatClient.exe"))
            || launcherInfo.absolutePath() != coreInfo.absolutePath()
            || launcherInfo.absolutePath() != restartInfo.absolutePath()
            || !safeDirectory(request.runRootDirectory)
            || !safeDirectory(request.resultDirectory)
            || QFileInfo(request.runRootDirectory).absoluteFilePath()
                == launcherInfo.absolutePath()
            || QFileInfo(request.resultDirectory).absoluteFilePath()
                == launcherInfo.absolutePath()) {
        result.error = QStringLiteral("Windows update handoff request is invalid");
        return result;
    }

    result.requestId = QUuid::createUuid().toString(QUuid::WithoutBraces).toLower();
    result.helperRunDirectory = QDir(request.runRootDirectory).filePath(
        QStringLiteral("run-%1").arg(result.requestId));
    result.resultFilePath = QDir(request.resultDirectory).filePath(
        QStringLiteral("result-%1.json").arg(result.requestId));
    if (QFileInfo::exists(result.helperRunDirectory)
            || QFileInfo::exists(result.resultFilePath)
            || !QDir(request.runRootDirectory).mkdir(
                QStringLiteral("run-%1").arg(result.requestId))) {
        result.error = QStringLiteral("Windows update helper directory could not be created");
        return result;
    }
    QFile::setPermissions(result.helperRunDirectory,
                          QFileDevice::ReadOwner | QFileDevice::WriteOwner
                              | QFileDevice::ExeOwner);

    const QString stagedLauncher = QDir(result.helperRunDirectory).filePath(
        QStringLiteral("ChatRoomUpdateLauncher.exe"));
    const QString stagedCore = QDir(result.helperRunDirectory).filePath(
        QStringLiteral("Qt6Core.dll"));
    if (!QFile::copy(request.installedLauncherPath, stagedLauncher)
            || !QFile::copy(request.qtCoreRuntimePath, stagedCore)) {
        if (cleanupRunDirectory(result.helperRunDirectory))
            result.helperRunDirectory.clear();
        result.error = QStringLiteral("Windows update helper runtime could not be staged");
        return result;
    }
    QFile::setPermissions(stagedLauncher,
                          QFileDevice::ReadOwner | QFileDevice::WriteOwner
                              | QFileDevice::ExeOwner);
    QFile::setPermissions(stagedCore,
                          QFileDevice::ReadOwner | QFileDevice::WriteOwner);

    const QString eventName = QStringLiteral(
        "Local\\ChatRoom.UpdateLauncher.Ready.%1").arg(result.requestId);
    HelperLaunch launch;
    launch.program = stagedLauncher;
    launch.workingDirectory = result.helperRunDirectory;
    launch.readyEventName = eventName;
    launch.arguments = {
        QStringLiteral("--parent-pid"),
        QString::number(QCoreApplication::applicationPid()),
        QStringLiteral("--installer"), request.installer.path,
        QStringLiteral("--installer-size"), QString::number(request.installer.size),
        QStringLiteral("--installer-sha256"),
        QString::fromLatin1(request.installer.sha256.toHex()),
        QStringLiteral("--signer-thumbprint-sha256"),
        QString::fromLatin1(request.installer.signerThumbprintSha256.toHex()),
        QStringLiteral("--restart-executable"), request.restartExecutablePath,
        QStringLiteral("--result-file"), result.resultFilePath,
        QStringLiteral("--request-id"), result.requestId,
        QStringLiteral("--ready-event"), eventName
    };

    const PlatformResult platform = launchHandshake(launch, HandshakeTimeoutMs);
    if (!platform.handshaken) {
        if (cleanupRunDirectory(result.helperRunDirectory))
            result.helperRunDirectory.clear();
        result.error = platform.error.isEmpty()
            ? QStringLiteral("Windows update helper handshake failed")
            : platform.error;
        return result;
    }
    result.readyToQuit = true;
    return result;
}

WindowsUpdateHandoffApplicationService::PlatformResult
WindowsUpdateHandoffApplicationService::launchAndHandshake(
        const HelperLaunch &launch, int timeoutMs) {
#ifndef Q_OS_WIN
    Q_UNUSED(launch)
    Q_UNUSED(timeoutMs)
    return {false, QStringLiteral("Windows update handoff requires Windows")};
#else
    const HANDLE ready = CreateEventW(
        nullptr, FALSE, FALSE,
        reinterpret_cast<LPCWSTR>(launch.readyEventName.utf16()));
    if (!ready)
        return {false, QStringLiteral("Windows update ready event could not be created")};
    qint64 helperPid = 0;
    const bool started = QProcess::startDetached(
        launch.program, launch.arguments, launch.workingDirectory, &helperPid);
    if (!started || helperPid <= 0) {
        CloseHandle(ready);
        return {false, QStringLiteral("Windows update helper could not be started")};
    }
    const DWORD wait = WaitForSingleObject(ready, static_cast<DWORD>(timeoutMs));
    CloseHandle(ready);
    return wait == WAIT_OBJECT_0
        ? PlatformResult{true, {}}
        : PlatformResult{false, wait == WAIT_TIMEOUT
              ? QStringLiteral("Windows update helper handshake timed out")
              : QStringLiteral("Windows update helper handshake wait failed")};
#endif
}

void WindowsUpdateHandoffApplicationService::handleFinished() {
    emit finished(m_watcher->result());
}
