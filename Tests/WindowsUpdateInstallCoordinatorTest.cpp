#include "WindowsUpdateInstallCoordinator.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QTemporaryDir>
#include <QTimer>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsUpdateInstallCoordinatorTest]" << message;
    return condition;
}

bool fixture(const QString &path) {
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write("fixture") == 7;
}

WindowsUpdateInstallCoordinator::Result waitFor(
        WindowsUpdateInstallCoordinator &coordinator) {
    WindowsUpdateInstallCoordinator::Result result;
    QEventLoop loop;
    QObject::connect(&coordinator, &WindowsUpdateInstallCoordinator::finished,
                     &loop, [&](const auto &value) {
        result = value;
        loop.quit();
    });
    QTimer::singleShot(3000, &loop, &QEventLoop::quit);
    loop.exec();
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    for (const QString &name : {QStringLiteral("install"), QStringLiteral("stage"),
                                QStringLiteral("state"), QStringLiteral("results"),
                                QStringLiteral("runs")})
        if (!QDir(root.path()).mkdir(name)) return 1;
    const QString install = root.filePath(QStringLiteral("install"));
    const QString installer = root.filePath(QStringLiteral("stage/update.exe"));
    const QString launcher = QDir(install).filePath(
        QStringLiteral("ChatRoomUpdateLauncher.exe"));
    const QString core = QDir(install).filePath(QStringLiteral("Qt6Core.dll"));
    const QString restart = QDir(install).filePath(QStringLiteral("ChatClient.exe"));
    if (!fixture(installer) || !fixture(launcher) || !fixture(core)
            || !fixture(restart)) return 1;

    WindowsUpdateInstallCoordinator coordinator(
        root.filePath(QStringLiteral("state")),
        root.filePath(QStringLiteral("results")),
        root.filePath(QStringLiteral("runs")),
        [](const auto &launch, int,
           const WindowsUpdateHandoffApplicationService::CommitAuthorizationFunction
               &authorize) {
            QString error;
            const bool committed = authorize(launch.requestId, &error);
            return WindowsUpdateHandoffApplicationService::PlatformResult{
                committed, error};
        });
    const QDateTime started = QDateTime::fromString(
        QStringLiteral("2026-08-12T10:00:00Z"), Qt::ISODate).toUTC();
    WindowsUpdateInstallCoordinator::Request request{
        {{installer, 7, QByteArray(32, '\xaa'), QByteArray(32, '\xbb')},
         launcher, core, restart, root.filePath(QStringLiteral("runs")),
         root.filePath(QStringLiteral("results"))},
        QStringLiteral("2.0.0"), started};
    QString error;
    if (!check(coordinator.start(request, &error), error)
            || !check(!coordinator.start(request, &error),
                      QStringLiteral("parallel install coordination was accepted"))) return 1;
    const auto authorized = waitFor(coordinator);
    UpdateLifecycleRepository lifecycle(
        root.filePath(QStringLiteral("state")),
        root.filePath(QStringLiteral("results")),
        root.filePath(QStringLiteral("runs")));
    const auto pending = lifecycle.consume(started.addSecs(1));
    if (!check(authorized.quitAuthorized && !authorized.requestId.isEmpty()
                   && pending.outcome
                       == UpdateLifecycleRepository::ConsumeOutcome::PendingResult
                   && pending.pending.requestId == authorized.requestId
                   && pending.pending.targetVersion == QStringLiteral("2.0.0"),
               authorized.error.isEmpty()
                   ? QStringLiteral("quit was authorized without durable pending state")
                   : authorized.error)) return 1;

    request.targetVersion = QStringLiteral("2.0.1");
    if (!coordinator.start(request, &error)) return 1;
    const auto blocked = waitFor(coordinator);
    if (!check(!blocked.quitAuthorized && !blocked.error.isEmpty(),
               QStringLiteral("quit was authorized after pending persistence failed")))
        return 1;

    qInfo() << "[WindowsUpdateInstallCoordinatorTest] PASS";
    return 0;
}
