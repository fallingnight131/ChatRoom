#pragma once

#include "UpdateManifestDecisionPolicy.h"
#include "UpdateManifestSignatureVerifier.h"
#include "UpdateStateRepository.h"

class UpdateManifestApplicationService {
public:
    struct Request {
        QByteArray canonicalManifest;
        QByteArray signature;
        QString currentVersion;
        QString channel;
        QDateTime nowUtc;
    };

    struct Result {
        UpdateManifestDecisionPolicy::Decision decision;
        QString stableDeviceId;
    };

    UpdateManifestApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory);

    Result evaluateAndAccept(const Request &request) const;

private:
    UpdateManifestSignatureVerifier m_signatureVerifier;
    UpdateStateRepository m_stateRepository;
};
