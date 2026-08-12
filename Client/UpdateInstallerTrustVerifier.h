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

    static IntegrityResult verifyIntegrity(const QString &path,
                                           qint64 expectedSize,
                                           const QByteArray &expectedSha256);
    static Result verify(const QString &path,
                         qint64 expectedSize,
                         const QByteArray &expectedSha256,
                         const QByteArray &expectedSignerSha256Thumbprint);
};
