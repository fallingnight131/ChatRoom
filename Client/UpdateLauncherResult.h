#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QString>

class UpdateLauncherResult {
public:
    enum class Outcome {
        Installed,
        InstallerFailed,
        TrustRejected,
        StartFailed,
        InstallerTimeout,
        InstallerWaitFailed,
        ParentOpenFailed,
        HandshakeFailed,
        ParentTimeout,
        ParentWaitFailed,
        UnsupportedPlatform
    };

    struct Value {
        QString requestId;
        Outcome outcome = Outcome::UnsupportedPlatform;
        quint32 installerExitCode = 0;
        QDateTime recordedAtUtc;
        QString error;
    };

    static bool parse(const QByteArray &bytes,
                      const QString &expectedRequestId,
                      const QDateTime &notBeforeUtc,
                      const QDateTime &nowUtc,
                      Value *result,
                      QString *error = nullptr);
    static QString outcomeName(Outcome outcome);
};
