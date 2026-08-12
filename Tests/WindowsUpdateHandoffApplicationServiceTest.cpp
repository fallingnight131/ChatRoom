#include "WindowsUpdateHandoffApplicationService.h"
#include "UpdateLauncherCommand.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>

#include <atomic>

namespace {
using Service = WindowsUpdateHandoffApplicationService;

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote()
        << "[WindowsUpdateHandoffApplicationServiceTest]" << message;
    return condition;
}

bool fixture(const QString &path, const QByteArray &bytes) {
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}

Service::Result waitFor(Service &service) {
    Service::Result result;
    QEventLoop loop;
    QObject::connect(&service, &Service::finished, &loop,
                     [&](const Service::Result &value) {
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
                                QStringLiteral("run"), QStringLiteral("results")}) {
        if (!QDir(root.path()).mkdir(name)) return 1;
    }
    const QString install = root.filePath(QStringLiteral("install"));
    const QString installer = root.filePath(QStringLiteral("stage/installer-test.exe"));
    const QString launcher = QDir(install).filePath(
        QStringLiteral("ChatRoomUpdateLauncher.exe"));
    const QString core = QDir(install).filePath(QStringLiteral("Qt6Core.dll"));
    const QString restart = QDir(install).filePath(QStringLiteral("ChatClient.exe"));
    if (!fixture(installer, "installer") || !fixture(launcher, "launcher")
            || !fixture(core, "core") || !fixture(restart, "client")) return 1;

    std::atomic_bool background{false};
    std::atomic_int launches{0};
    Service service([&](const Service::HelperLaunch &launch, int timeoutMs) {
        ++launches;
        background = QThread::currentThread() != app.thread();
        UpdateLauncherCommand parsed;
        QString error;
        QStringList parseArguments = launch.arguments;
        const bool parentBound = parseArguments.value(0) == QStringLiteral("--parent-pid")
            && parseArguments.value(1)
                == QString::number(QCoreApplication::applicationPid());
        parseArguments[1] = QStringLiteral("4294967295");
        const bool valid = timeoutMs == 15000
            && parentBound
            && QFile::exists(launch.program)
            && QFile::exists(QDir(launch.workingDirectory).filePath(
                QStringLiteral("Qt6Core.dll")))
            && UpdateLauncherCommand::parse(parseArguments, &parsed, &error)
            && parsed.readyEventName == launch.readyEventName;
        return Service::PlatformResult{
            valid, valid ? QString() : (error.isEmpty()
                ? QStringLiteral("fixture handshake rejected") : error)};
    });
    Service::Request request{
        {installer, 9, QByteArray(32, '\xaa'), QByteArray(32, '\xbb')},
        launcher, core, restart, root.filePath(QStringLiteral("run")),
        root.filePath(QStringLiteral("results"))};
    QString error;
    if (!check(service.start(request, &error), error)
            || !check(!service.start(request, &error),
                      QStringLiteral("parallel handoff was allowed"))) return 1;
    const auto ready = waitFor(service);
    if (!check(ready.readyToQuit && !ready.requestId.isEmpty()
                   && ready.resultFilePath.endsWith(
                       QStringLiteral("result-%1.json").arg(ready.requestId))
                   && QDir(ready.helperRunDirectory).exists()
                   && background.load() && launches.load() == 1,
               ready.error.isEmpty() ? QStringLiteral("handoff evidence is incomplete")
                                     : ready.error)) return 1;
    QDir(ready.helperRunDirectory).removeRecursively();

    QFile::remove(core);
    if (!service.start(request)) return 1;
    const auto rejected = waitFor(service);
    if (!check(!rejected.readyToQuit && !rejected.error.isEmpty()
                   && launches.load() == 1
                   && QDir(root.filePath(QStringLiteral("run"))).entryList(
                          QDir::Dirs | QDir::NoDotAndDotDot).isEmpty(),
               QStringLiteral("invalid runtime reached helper launch"))) return 1;

    qInfo() << "[WindowsUpdateHandoffApplicationServiceTest] PASS";
    return 0;
}
