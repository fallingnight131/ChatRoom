#include "WindowsDeviceIdentityRepository.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QFileInfo>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsDeviceIdentityRepositoryTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    if (!root.isValid()) return 1;
    const QString directory = root.filePath(QStringLiteral("identity"));
    WindowsDeviceIdentityRepository repository(directory);
    QString first;
    QString second;
    QString error;
    if (!check(repository.loadOrCreate(&first, &error), error)
            || !check(!first.isEmpty(), QStringLiteral("identity was not created"))
            || !check(repository.loadOrCreate(&second, &error), error)
            || !check(first == second, QStringLiteral("identity changed after restart"))) return 1;

    const QFileInfo file(directory + QStringLiteral("/device-identity.json"));
    if (!check((file.permissions() & (QFileDevice::ReadGroup | QFileDevice::WriteGroup
                                      | QFileDevice::ReadOther | QFileDevice::WriteOther)) == 0,
               QStringLiteral("identity file is not owner-only"))) return 1;

    QFile corrupt(file.filePath());
    if (!corrupt.open(QIODevice::WriteOnly | QIODevice::Truncate)
            || corrupt.write("{broken") != 7) return 1;
    corrupt.close();
    if (!check(!repository.loadOrCreate(&second, &error) && second.isEmpty(),
               QStringLiteral("corrupt identity was silently regenerated"))) return 1;

    qInfo() << "[WindowsDeviceIdentityRepositoryTest] PASS";
    return 0;
}
