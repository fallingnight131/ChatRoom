#include "UpdateStateRepository.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QFileInfo>
#include <QTemporaryDir>

namespace {
using Repository = UpdateStateRepository;

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateStateRepositoryTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    if (!root.isValid()) return 1;
    const QString directory = root.filePath(QStringLiteral("state"));
    Repository repository(directory);
    Repository::State initial;
    QString error;
    if (!check(repository.loadOrCreate(&initial, &error), error)
            || !check(!initial.stableDeviceId.isEmpty(),
                      QStringLiteral("stable device ID was not created"))
            || !check(initial.channels.value(QStringLiteral("stable")).sequence == 0,
                      QStringLiteral("stable channel did not start empty"))
            || !check((QFileInfo(directory + QStringLiteral("/update-state.json")).permissions()
                           & (QFileDevice::ReadGroup | QFileDevice::WriteGroup
                              | QFileDevice::ReadOther | QFileDevice::WriteOther)) == 0,
                      QStringLiteral("update state file is not owner-only"))) return 1;

    Repository::State reloaded;
    if (!check(repository.loadOrCreate(&reloaded, &error), error)
            || !check(reloaded.stableDeviceId == initial.stableDeviceId,
                      QStringLiteral("stable device ID changed after restart"))) return 1;

    const QByteArray first(32, '\x11');
    const QByteArray second(32, '\x22');
    if (!check(repository.accept(QStringLiteral("stable"), 42, first, &reloaded, &error)
                   == Repository::Acceptance::Stored,
               error)
            || !check(reloaded.channels.value(QStringLiteral("stable")).sequence == 42,
                      QStringLiteral("accepted sequence was not returned"))
            || !check(repository.accept(QStringLiteral("stable"), 42, first, nullptr, &error)
                          == Repository::Acceptance::Idempotent,
                      QStringLiteral("identical watermark was not idempotent"))
            || !check(repository.accept(QStringLiteral("stable"), 42, second, nullptr, &error)
                          == Repository::Acceptance::Rejected,
                      QStringLiteral("same-sequence conflict was accepted"))
            || !check(repository.accept(QStringLiteral("stable"), 41, first, nullptr, &error)
                          == Repository::Acceptance::Rejected,
                      QStringLiteral("lower-sequence replay was accepted"))
            || !check(repository.accept(QStringLiteral("stable"), 43, second, nullptr, &error)
                          == Repository::Acceptance::Stored,
                      error)) return 1;

    if (!check(repository.loadOrCreate(&reloaded, &error), error)
            || !check(reloaded.channels.value(QStringLiteral("stable")).sequence == 43
                          && reloaded.channels.value(QStringLiteral("stable")).manifestSha256
                              == second,
                      QStringLiteral("new watermark did not survive restart"))) return 1;

    QFile corrupt(directory + QStringLiteral("/update-state.json"));
    if (!corrupt.open(QIODevice::WriteOnly | QIODevice::Truncate)
            || corrupt.write("{broken") != 7) return 1;
    corrupt.close();
    if (!check(!repository.loadOrCreate(&reloaded, &error),
               QStringLiteral("corrupt state was silently regenerated"))) return 1;

    qInfo() << "[UpdateStateRepositoryTest] PASS";
    return 0;
}
