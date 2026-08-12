#include "UpdateLauncherCommand.h"
#include "UpdateInstallerTrustVerifier.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QSaveFile>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace {
bool writeResult(const UpdateLauncherCommand &command,
                 const QString &outcome, quint32 installerExitCode,
                 const QString &error) {
    const QJsonObject object{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("requestId"), command.requestId},
        {QStringLiteral("outcome"), outcome},
        {QStringLiteral("installerExitCode"), static_cast<qint64>(installerExitCode)},
        {QStringLiteral("recordedAt"), QDateTime::currentDateTimeUtc().toString(Qt::ISODate)},
        {QStringLiteral("error"), error}
    };
    QSaveFile file(command.resultFilePath);
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly)) return false;
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const QByteArray bytes = QJsonDocument(object).toJson(QJsonDocument::Compact) + '\n';
    return file.write(bytes) == bytes.size() && file.commit();
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    UpdateLauncherCommand command;
    QString error;
    if (!UpdateLauncherCommand::parse(app.arguments().mid(1), &command, &error))
        return 2;

#ifndef Q_OS_WIN
    writeResult(command, QStringLiteral("unsupported-platform"), 0,
                QStringLiteral("Windows update launcher requires Windows"));
    return 6;
#else
    const HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, command.parentProcessId);
    if (!parent) {
        writeResult(command, QStringLiteral("parent-open-failed"), 0,
                    QStringLiteral("client process could not be observed"));
        return 3;
    }
    const HANDLE readyEvent = OpenEventW(
        EVENT_MODIFY_STATE, FALSE,
        reinterpret_cast<LPCWSTR>(command.readyEventName.utf16()));
    if (!readyEvent || !SetEvent(readyEvent)) {
        if (readyEvent) CloseHandle(readyEvent);
        CloseHandle(parent);
        writeResult(command, QStringLiteral("handshake-failed"), 0,
                    QStringLiteral("client/helper handshake failed"));
        return 3;
    }
    CloseHandle(readyEvent);
    const DWORD parentWait = WaitForSingleObject(parent, 2 * 60 * 1000);
    CloseHandle(parent);
    if (parentWait != WAIT_OBJECT_0) {
        writeResult(command, parentWait == WAIT_TIMEOUT
                        ? QStringLiteral("parent-timeout")
                        : QStringLiteral("parent-wait-failed"),
                    0, QStringLiteral("client did not exit normally before update"));
        return 3;
    }

    const auto launch = UpdateInstallerTrustVerifier::verifyLaunchAndWait(
        command.installerPath, command.installerSize,
        command.installerSha256, command.signerThumbprintSha256);
    using LaunchOutcome = UpdateInstallerTrustVerifier::LaunchOutcome;
    QString outcome;
    int returnCode = 4;
    bool safeToRemoveInstaller = false;
    if (launch.outcome == LaunchOutcome::Exited) {
        outcome = launch.processExitCode == 0
            ? QStringLiteral("installed") : QStringLiteral("installer-failed");
        returnCode = launch.processExitCode == 0 ? 0 : 5;
        safeToRemoveInstaller = true;
    } else if (launch.outcome == LaunchOutcome::TrustRejected) {
        outcome = QStringLiteral("trust-rejected");
        safeToRemoveInstaller = true;
    } else if (launch.outcome == LaunchOutcome::StartFailed) {
        outcome = QStringLiteral("start-failed");
        safeToRemoveInstaller = true;
    } else if (launch.outcome == LaunchOutcome::TimedOut) {
        outcome = QStringLiteral("installer-timeout");
    } else {
        outcome = QStringLiteral("installer-wait-failed");
    }
    if (safeToRemoveInstaller) QFile::remove(command.installerPath);
    if (!writeResult(command, outcome, launch.processExitCode, launch.error))
        return 7;
    if (returnCode == 0
            && !QProcess::startDetached(command.restartExecutablePath, {}))
        return 8;
    return returnCode;
#endif
}
