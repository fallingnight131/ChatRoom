#include "WindowsUpdateRuntimePaths.h"
#include "WindowsUpdateStartupService.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTemporaryDir>

namespace {
const QString Id = QStringLiteral("123e4567-e89b-42d3-a456-426614174000");
const QDateTime Started = QDateTime::fromString(
    QStringLiteral("2026-08-12T10:00:00Z"), Qt::ISODate).toUTC();

bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsUpdateStartupServiceTest]" << message;
    return condition;
}

bool writeResult(const QString &directory, const QString &outcome,
                 int exitCode, const QString &recordedAt) {
    QFile file(QDir(directory).filePath(
        QStringLiteral("result-%1.json").arg(Id)));
    const QByteArray bytes = QJsonDocument(QJsonObject{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("requestId"), Id},
        {QStringLiteral("outcome"), outcome},
        {QStringLiteral("installerExitCode"), exitCode},
        {QStringLiteral("recordedAt"), recordedAt},
        {QStringLiteral("error"), outcome == QStringLiteral("installed")
            ? QString() : QStringLiteral("fixture failure")}
    }).toJson(QJsonDocument::Compact);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}

struct Fixture {
    QTemporaryDir root;
    WindowsUpdateRuntimePaths paths = WindowsUpdateRuntimePaths::fromAppLocalData(
        root.path());

    bool prepare(const QString &target = QStringLiteral("2.0.0")) {
        for (const QString &directory : {paths.lifecycleStateDirectory,
                                         paths.resultDirectory,
                                         paths.runRootDirectory})
            if (!QDir().mkpath(directory)) return false;
        if (!QDir(paths.runRootDirectory).mkdir(
                QStringLiteral("run-%1").arg(Id))) return false;
        UpdateLifecycleRepository lifecycle(
            paths.lifecycleStateDirectory, paths.resultDirectory,
            paths.runRootDirectory);
        return lifecycle.recordPending({Id, target, Started});
    }

    WindowsUpdateStartupService service() const {
        return WindowsUpdateStartupService(
            paths.lifecycleStateDirectory, paths.resultDirectory,
            paths.runRootDirectory);
    }
};
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);

    Fixture empty;
    if (!check(QFileInfo(empty.paths.manifestStateDirectory).fileName()
                   == QStringLiteral("state")
               && QFileInfo(empty.paths.lifecycleStateDirectory).fileName()
                   == QStringLiteral("lifecycle")
               && empty.paths.manifestStateDirectory
                   != empty.paths.lifecycleStateDirectory,
               QStringLiteral("manifest replay and install lifecycle paths overlap")))
        return 1;
    if (!check(empty.service().inspect(QStringLiteral("1.0.0"), Started).outcome
                   == WindowsUpdateStartupService::Outcome::None,
               QStringLiteral("empty lifecycle was not silent"))) return 1;

    Fixture pending;
    if (!pending.prepare()) return 1;
    if (!check(pending.service().inspect(
                   QStringLiteral("1.0.0"), Started.addSecs(60)).outcome
                   == WindowsUpdateStartupService::Outcome::UpdateInProgress,
               QStringLiteral("recent pending update did not block startup"))
            || !check(pending.service().inspect(
                          QStringLiteral("1.0.0"), Started.addSecs(21 * 60)).outcome
                          == WindowsUpdateStartupService::Outcome::StalePending,
                      QStringLiteral("stale pending update blocked indefinitely"))) return 1;

    Fixture installed;
    if (!installed.prepare()
            || !writeResult(installed.paths.resultDirectory,
                            QStringLiteral("installed"), 0,
                            QStringLiteral("2026-08-12T10:01:00Z"))) return 1;
    if (!check(installed.service().inspect(
                   QStringLiteral("2.0.0"), Started.addSecs(120)).outcome
                   == WindowsUpdateStartupService::Outcome::Installed,
               QStringLiteral("matching installed version was not accepted"))) return 1;

    Fixture mismatch;
    if (!mismatch.prepare()
            || !writeResult(mismatch.paths.resultDirectory,
                            QStringLiteral("installed"), 0,
                            QStringLiteral("2026-08-12T10:01:00Z"))) return 1;
    if (!check(mismatch.service().inspect(
                   QStringLiteral("1.0.0"), Started.addSecs(120)).outcome
                   == WindowsUpdateStartupService::Outcome::Rejected,
               QStringLiteral("version mismatch was reported as success"))) return 1;

    Fixture failed;
    if (!failed.prepare()
            || !writeResult(failed.paths.resultDirectory,
                            QStringLiteral("installer-failed"), 1603,
                            QStringLiteral("2026-08-12T10:01:00Z"))) return 1;
    const auto failure = failed.service().inspect(
        QStringLiteral("1.0.0"), Started.addSecs(120));
    if (!check(failure.outcome == WindowsUpdateStartupService::Outcome::Failed
                   && failure.installerExitCode == 1603
                   && failure.launcherOutcome == QStringLiteral("installer-failed"),
               QStringLiteral("installer failure evidence was lost"))) return 1;

    qInfo() << "[WindowsUpdateStartupServiceTest] PASS";
    return 0;
}
