#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QJsonObject>
#include <QString>

class UpdateManifestDecisionPolicy {
public:
    enum class Outcome {
        Rejected,
        NoUpdate,
        ManualUpdateRequired,
        DeferredByRollout,
        Eligible
    };

    struct Context {
        QString currentVersion;
        QString channel;
        qint64 highestAcceptedSequence = 0;
        QByteArray highestAcceptedManifestSha256;
        QString stableDeviceId;
        QDateTime nowUtc;
    };

    struct Decision {
        Outcome outcome = Outcome::Rejected;
        QString targetVersion;
        QString installerUrl;
        qint64 installerSize = 0;
        QByteArray installerSha256;
        QByteArray authenticodeSha256Thumbprint;
        qint64 acceptedSequence = 0;
        QByteArray acceptedManifestSha256;
        int rolloutBucket = -1;
        QString error;
    };

    static Decision evaluate(const QByteArray &canonicalManifest,
                             const QJsonObject &verifiedManifest,
                             const Context &context);
};
