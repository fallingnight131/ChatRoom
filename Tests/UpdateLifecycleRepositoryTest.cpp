#include "UpdateLifecycleRepository.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTemporaryDir>

namespace {
const QString Id = QStringLiteral("123e4567-e89b-42d3-a456-426614174000");

bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[UpdateLifecycleRepositoryTest]" << message;
    return condition;
}

bool write(const QString &path, const QByteArray &bytes) {
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    for (const QString &name : {QStringLiteral("state"), QStringLiteral("results"),
                                QStringLiteral("runs")})
        if (!QDir(root.path()).mkdir(name)) return 1;
    UpdateLifecycleRepository repository(
        root.filePath(QStringLiteral("state")),
        root.filePath(QStringLiteral("results")),
        root.filePath(QStringLiteral("runs")));
    const QDateTime started = QDateTime::fromString(
        QStringLiteral("2026-08-12T10:00:00Z"), Qt::ISODate).toUTC();
    QString error;
    const QString run = root.filePath(QStringLiteral("runs/run-%1").arg(Id));
    if (!QDir(root.filePath(QStringLiteral("runs"))).mkdir(
            QStringLiteral("run-%1").arg(Id))) return 1;
    if (!check(repository.recordPending({Id, QStringLiteral("2.0.0"), started}, &error), error)
            || !check(!repository.recordPending(
                          {Id, QStringLiteral("2.0.0"), started}, &error),
                      QStringLiteral("parallel pending update was accepted"))) return 1;
    auto pending = repository.consume(started.addSecs(30));
    if (!check(pending.outcome
                   == UpdateLifecycleRepository::ConsumeOutcome::PendingResult,
               pending.error)) return 1;

    const QByteArray result = QJsonDocument(QJsonObject{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("requestId"), Id},
        {QStringLiteral("outcome"), QStringLiteral("installed")},
        {QStringLiteral("installerExitCode"), 0},
        {QStringLiteral("recordedAt"), QStringLiteral("2026-08-12T10:01:00Z")},
        {QStringLiteral("error"), QString()}
    }).toJson(QJsonDocument::Compact);
    const QString resultPath = root.filePath(
        QStringLiteral("results/result-%1.json").arg(Id));
    if (!write(resultPath, result)) return 1;
    const auto consumed = repository.consume(started.addSecs(120));
    if (!check(consumed.outcome
                   == UpdateLifecycleRepository::ConsumeOutcome::Completed
                   && consumed.pending.targetVersion == QStringLiteral("2.0.0")
                   && consumed.result.outcome == UpdateLauncherResult::Outcome::Installed
                   && !QFile::exists(resultPath) && !QDir(run).exists(),
               consumed.error.isEmpty() ? QStringLiteral("result was not consumed once")
                                        : consumed.error)
            || !check(repository.consume(started.addSecs(121)).outcome
                          == UpdateLifecycleRepository::ConsumeOutcome::None,
                      QStringLiteral("consumed result replayed"))) return 1;

    if (!QDir(root.filePath(QStringLiteral("runs"))).mkdir(
            QStringLiteral("run-%1").arg(Id))
            || !repository.recordPending({Id, QStringLiteral("2.0.1"), started}, &error)
            || !write(resultPath, QByteArrayLiteral("{}"))) return 1;
    const auto rejected = repository.consume(started.addSecs(120));
    if (!check(rejected.outcome
                   == UpdateLifecycleRepository::ConsumeOutcome::Rejected
                   && QFile::exists(resultPath),
               QStringLiteral("invalid evidence was discarded or accepted"))) return 1;

    qInfo() << "[UpdateLifecycleRepositoryTest] PASS";
    return 0;
}
