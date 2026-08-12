#pragma once

#include "UpdateLifecycleRepository.h"

class WindowsUpdateStartupService {
public:
    enum class Outcome {
        None,
        UpdateInProgress,
        StalePending,
        Installed,
        Failed,
        Rejected
    };

    struct Result {
        Outcome outcome = Outcome::None;
        QString targetVersion;
        QString launcherOutcome;
        quint32 installerExitCode = 0;
        QString error;
    };

    WindowsUpdateStartupService(QString lifecycleStateDirectory,
                                QString resultDirectory,
                                QString runRootDirectory);

    Result inspect(const QString &currentVersion,
                   const QDateTime &nowUtc) const;

private:
    UpdateLifecycleRepository m_lifecycle;
};
