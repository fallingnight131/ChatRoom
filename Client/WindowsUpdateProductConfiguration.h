#pragma once

#include "UpdateManifestSignatureVerifier.h"

#include <QUrl>

class WindowsUpdateProductConfiguration {
public:
    struct Key {
        QString id;
        QString publicKeyHex;
    };

    struct Value {
        bool enabled = false;
        QString channel;
        QUrl manifestUrl;
        QUrl signatureUrl;
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys;
        QString error;
    };

    static Value fromBuild();
    static Value validate(const QString &channel,
                          const QString &manifestUrl,
                          const QList<Key> &keys);
};
