#pragma once

#include <QByteArray>
#include <QString>

class UpdateInstallerTrustVerifier {
public:
    struct IntegrityResult {
        bool valid = false;
        QString error;
    };

    enum class Outcome {
        Verified,
        IntegrityRejected,
        AuthenticodeRejected,
        UnsupportedPlatform
    };

    struct Result {
        Outcome outcome = Outcome::IntegrityRejected;
        QString error;
    };

    enum class LaunchOutcome {
        Exited,
        TrustRejected,
        StartFailed,
        WaitFailed,
        TimedOut,
        UnsupportedPlatform
    };

    struct LaunchResult {
        LaunchOutcome outcome = LaunchOutcome::TrustRejected;
        quint32 processExitCode = 0;
        QString error;
    };

    static IntegrityResult verifyIntegrity(const QString &path,
                                           qint64 expectedSize,
                                           const QByteArray &expectedSha256);
    static Result verify(const QString &path,
                         qint64 expectedSize,
                         const QByteArray &expectedSha256,
                         const QByteArray &expectedSignerSha256Thumbprint);
    static LaunchResult verifyLaunchAndWait(
        const QString &path,
        qint64 expectedSize,
        const QByteArray &expectedSha256,
        const QByteArray &expectedSignerSha256Thumbprint,
        int waitTimeoutMs = 15 * 60 * 1000);
};
