#include "WindowsClientInstanceGuard.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[WindowsClientInstanceGuardTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    if (!check(WindowsClientInstanceGuard::mutexName()
                   == QStringLiteral("Local\\ChatRoom.WindowsClient.Running.v1"),
               QStringLiteral("installer/client mutex contract changed"))) return 1;
#ifdef Q_OS_WIN
    auto *first = new WindowsClientInstanceGuard;
    WindowsClientInstanceGuard second;
    QString error;
    if (!check(first->acquire(&error) == WindowsClientInstanceGuard::Result::Acquired, error)
            || !check(first->isAcquired(), QStringLiteral("first guard did not retain mutex"))
            || !check(second.acquire(&error)
                          == WindowsClientInstanceGuard::Result::AlreadyRunning,
                      QStringLiteral("second client instance was not rejected"))) return 1;
    delete first;
    if (!check(second.acquire(&error) == WindowsClientInstanceGuard::Result::Acquired,
               QStringLiteral("mutex was not released when client exited"))) return 1;
#else
    WindowsClientInstanceGuard guard;
    if (!check(guard.acquire() == WindowsClientInstanceGuard::Result::UnsupportedPlatform,
               QStringLiteral("non-Windows host claimed a Windows mutex"))) return 1;
#endif
    qInfo() << "[WindowsClientInstanceGuardTest] PASS";
    return 0;
}
