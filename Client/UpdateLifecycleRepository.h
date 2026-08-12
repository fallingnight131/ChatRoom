#pragma once

#include "UpdateLauncherResult.h"

class UpdateLifecycleRepository {
public:
    struct Pending {
        QString requestId;
        QString targetVersion;
        QDateTime createdAtUtc;
    };

    enum class ConsumeOutcome { None, PendingResult, Completed, Rejected };

    struct Consumption {
        ConsumeOutcome outcome = ConsumeOutcome::None;
        Pending pending;
        UpdateLauncherResult::Value result;
        QString error;
    };

    UpdateLifecycleRepository(QString stateDirectory,
                              QString resultDirectory,
                              QString runRootDirectory);

    bool recordPending(const Pending &pending, QString *error = nullptr) const;
    Consumption consume(const QDateTime &nowUtc) const;

private:
    bool prepare(QString *error) const;
    bool readPending(Pending *pending, bool allowMissing, QString *error) const;

    QString m_stateDirectory;
    QString m_resultDirectory;
    QString m_runRootDirectory;
};
