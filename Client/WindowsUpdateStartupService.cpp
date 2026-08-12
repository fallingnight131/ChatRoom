#include "WindowsUpdateStartupService.h"

#include <QRegularExpression>

#include <utility>

namespace {
constexpr qint64 RecentPendingSeconds = 20 * 60;
constexpr qint64 ClockSkewSeconds = 5 * 60;
const QRegularExpression Version(QStringLiteral(
    R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$)"));
}

WindowsUpdateStartupService::WindowsUpdateStartupService(
        QString lifecycleStateDirectory, QString resultDirectory,
        QString runRootDirectory)
    : m_lifecycle(std::move(lifecycleStateDirectory),
                  std::move(resultDirectory),
                  std::move(runRootDirectory)) {}

WindowsUpdateStartupService::Result WindowsUpdateStartupService::inspect(
        const QString &currentVersion, const QDateTime &nowUtc) const {
    Result result;
    if (!Version.match(currentVersion).hasMatch()
            || !nowUtc.isValid() || nowUtc.timeSpec() != Qt::UTC) {
        result.outcome = Outcome::Rejected;
        result.error = QStringLiteral("Windows update startup context is invalid");
        return result;
    }

    const auto consumed = m_lifecycle.consume(nowUtc);
    result.targetVersion = consumed.pending.targetVersion;
    result.error = consumed.error;
    using ConsumeOutcome = UpdateLifecycleRepository::ConsumeOutcome;
    if (consumed.outcome == ConsumeOutcome::None) return result;
    if (consumed.outcome == ConsumeOutcome::Rejected) {
        result.outcome = Outcome::Rejected;
        return result;
    }
    if (consumed.outcome == ConsumeOutcome::PendingResult) {
        const qint64 age = consumed.pending.createdAtUtc.secsTo(nowUtc);
        if (age < -ClockSkewSeconds) {
            result.outcome = Outcome::Rejected;
            result.error = QStringLiteral("pending update timestamp is in the future");
        } else if (age <= RecentPendingSeconds) {
            result.outcome = Outcome::UpdateInProgress;
        } else {
            result.outcome = Outcome::StalePending;
            result.error = QStringLiteral("pending update has no result after the bounded window");
        }
        return result;
    }

    result.launcherOutcome = UpdateLauncherResult::outcomeName(
        consumed.result.outcome);
    result.installerExitCode = consumed.result.installerExitCode;
    if (consumed.result.outcome == UpdateLauncherResult::Outcome::Installed) {
        if (consumed.pending.targetVersion == currentVersion) {
            result.outcome = Outcome::Installed;
        } else {
            result.outcome = Outcome::Rejected;
            result.error = QStringLiteral(
                "installer reported success but the running version does not match");
        }
    } else {
        result.outcome = Outcome::Failed;
        if (result.error.isEmpty()) result.error = consumed.result.error;
    }
    return result;
}
