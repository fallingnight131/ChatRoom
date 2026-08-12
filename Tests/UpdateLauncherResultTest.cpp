#include "UpdateLauncherResult.h"

#include <QCoreApplication>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QList>

namespace {
const QString RequestId = QStringLiteral("123e4567-e89b-42d3-a456-426614174000");

bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[UpdateLauncherResultTest]" << message;
    return condition;
}

QByteArray record(const QString &outcome = QStringLiteral("installed"),
                  qint64 exitCode = 0,
                  const QString &recordedAt = QStringLiteral("2026-08-12T10:01:00Z"),
                  const QString &error = {}) {
    return QJsonDocument(QJsonObject{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("requestId"), RequestId},
        {QStringLiteral("outcome"), outcome},
        {QStringLiteral("installerExitCode"), exitCode},
        {QStringLiteral("recordedAt"), recordedAt},
        {QStringLiteral("error"), error}
    }).toJson(QJsonDocument::Compact);
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const QDateTime started = QDateTime::fromString(
        QStringLiteral("2026-08-12T10:00:00Z"), Qt::ISODate).toUTC();
    const QDateTime now = started.addSecs(120);
    UpdateLauncherResult::Value parsed;
    QString error;

    if (!check(UpdateLauncherResult::parse(
                   record(), RequestId, started, now, &parsed, &error)
                   && parsed.outcome == UpdateLauncherResult::Outcome::Installed
                   && parsed.installerExitCode == 0
                   && UpdateLauncherResult::outcomeName(parsed.outcome)
                       == QStringLiteral("installed"),
               error.isEmpty() ? QStringLiteral("valid result was not preserved") : error)
            || !check(UpdateLauncherResult::parse(
                          record(QStringLiteral("installer-failed"), 1603),
                          RequestId, started, now, &parsed, &error)
                          && parsed.outcome
                              == UpdateLauncherResult::Outcome::InstallerFailed
                          && parsed.installerExitCode == 1603,
                      error.isEmpty() ? QStringLiteral("failure exit code was lost") : error))
        return 1;
    if (!check(UpdateLauncherResult::parse(
                   record(QStringLiteral("handoff-aborted")), RequestId,
                   started, now, &parsed, &error)
                   && parsed.outcome
                       == UpdateLauncherResult::Outcome::HandoffAborted,
               error.isEmpty() ? QStringLiteral("aborted handoff was not preserved")
                               : error)) return 1;

    QJsonObject extra = QJsonDocument::fromJson(record()).object();
    extra.insert(QStringLiteral("futureField"), true);
    const QList<QByteArray> rejected{
        record(QStringLiteral("installed"), 1),
        record(QStringLiteral("installer-failed"), 0),
        record(QStringLiteral("trust-rejected"), 5),
        record(QStringLiteral("installed"), 0,
               QStringLiteral("2026-08-12T11:00:00Z")),
        record(QStringLiteral("installed"), 0,
               QStringLiteral("2026-08-12T09:50:00Z")),
        record(QStringLiteral("unknown")),
        record(QStringLiteral("start-failed"), 0,
               QStringLiteral("2026-08-12T10:01:00Z"), QStringLiteral("bad\nline")),
        QJsonDocument(extra).toJson(QJsonDocument::Compact)
    };
    for (const QByteArray &bytes : rejected) {
        if (!check(!UpdateLauncherResult::parse(
                       bytes, RequestId, started, now, &parsed, &error)
                       && !error.isEmpty(),
                   QStringLiteral("invalid launcher result was accepted"))) return 1;
    }
    if (!check(!UpdateLauncherResult::parse(
                   record(), QStringLiteral("223e4567-e89b-42d3-a456-426614174000"),
                   started, now, &parsed, &error),
               QStringLiteral("mismatched request UUID was accepted"))
            || !check(!UpdateLauncherResult::parse(
                          QByteArray(16 * 1024 + 1, 'x'), RequestId,
                          started, now, &parsed, &error),
                      QStringLiteral("oversized result was accepted"))) return 1;

    qInfo() << "[UpdateLauncherResultTest] PASS";
    return 0;
}
