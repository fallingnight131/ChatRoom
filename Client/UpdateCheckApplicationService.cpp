#include "UpdateCheckApplicationService.h"

#include <utility>

namespace {
using PolicyOutcome = UpdateManifestDecisionPolicy::Outcome;
}

UpdateCheckApplicationService::UpdateCheckApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory, QString stagingDirectory, QObject *parent)
    : QObject(parent),
      m_fetch(new UpdateManifestFetchTransport(this)),
      m_preparation(new UpdatePreparationApplicationService(
          std::move(trustedKeys), std::move(stateDirectory),
          std::move(stagingDirectory), this)) {
    connect(m_fetch, &UpdateManifestFetchTransport::finished, this,
            &UpdateCheckApplicationService::handleManifestFetch);
    connect(m_preparation, &UpdatePreparationApplicationService::progress,
            this, &UpdateCheckApplicationService::progress);
    connect(m_preparation, &UpdatePreparationApplicationService::finished, this,
            &UpdateCheckApplicationService::handlePreparation);
}

UpdateCheckApplicationService::UpdateCheckApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory, QString stagingDirectory,
        QNetworkAccessManager *networkManager,
        UpdatePreparationApplicationService::TrustFunction trustFunction,
        QObject *parent)
    : QObject(parent),
      m_fetch(new UpdateManifestFetchTransport(networkManager, this)),
      m_preparation(new UpdatePreparationApplicationService(
          std::move(trustedKeys), std::move(stateDirectory),
          std::move(stagingDirectory), networkManager,
          std::move(trustFunction), this)) {
    connect(m_fetch, &UpdateManifestFetchTransport::finished, this,
            &UpdateCheckApplicationService::handleManifestFetch);
    connect(m_preparation, &UpdatePreparationApplicationService::progress,
            this, &UpdateCheckApplicationService::progress);
    connect(m_preparation, &UpdatePreparationApplicationService::finished, this,
            &UpdateCheckApplicationService::handlePreparation);
}

bool UpdateCheckApplicationService::start(
        const Request &request, QString *error) {
    if (error) error->clear();
    if (isActive()) {
        if (error) *error = QStringLiteral("update check is already active");
        return false;
    }
    m_request = request;
    m_targetVersion.clear();
    if (!m_fetch->start({request.manifestUrl, request.signatureUrl}, error)) {
        m_request = {};
        return false;
    }
    return true;
}

void UpdateCheckApplicationService::cancel() {
    if (m_fetch->isActive()) m_fetch->cancel();
    else if (m_preparation->isActive()) m_preparation->cancel();
}

bool UpdateCheckApplicationService::isActive() const {
    return m_fetch->isActive() || m_preparation->isActive();
}

void UpdateCheckApplicationService::handleManifestFetch(
        UpdateManifestFetchTransport::Outcome outcome,
        const QByteArray &manifestBytes, const QByteArray &signature,
        const QString &error) {
    if (outcome == UpdateManifestFetchTransport::Outcome::Cancelled) {
        finish(Outcome::Cancelled, {}, {}, error);
        return;
    }
    if (outcome != UpdateManifestFetchTransport::Outcome::Succeeded) {
        finish(Outcome::Rejected, {}, {}, error);
        return;
    }

    UpdateManifestApplicationService::Request preparationRequest;
    preparationRequest.canonicalManifest = manifestBytes;
    preparationRequest.signature = signature;
    preparationRequest.currentVersion = m_request.currentVersion;
    preparationRequest.channel = m_request.channel;
    preparationRequest.nowUtc = m_request.nowUtc;
    const auto start = m_preparation->prepare(preparationRequest);
    m_targetVersion = start.decision.targetVersion;
    if (start.downloadStarted) return;

    switch (start.decision.outcome) {
    case PolicyOutcome::NoUpdate:
        finish(Outcome::NoUpdate, {}, m_targetVersion);
        break;
    case PolicyOutcome::ManualUpdateRequired:
        finish(Outcome::ManualUpdateRequired, {}, m_targetVersion,
               start.decision.error);
        break;
    case PolicyOutcome::DeferredByRollout:
        finish(Outcome::DeferredByRollout, {}, m_targetVersion);
        break;
    case PolicyOutcome::Rejected:
        finish(Outcome::Rejected, {}, m_targetVersion,
               start.decision.error.isEmpty()
                   ? QStringLiteral("update manifest was rejected")
                   : start.decision.error);
        break;
    case PolicyOutcome::Eligible:
        finish(Outcome::Rejected, {}, m_targetVersion,
               QStringLiteral("eligible update preparation did not start"));
        break;
    }
}

void UpdateCheckApplicationService::handlePreparation(
        UpdatePreparationApplicationService::Outcome outcome,
        const QString &path, const QString &error) {
    if (outcome == UpdatePreparationApplicationService::Outcome::Ready)
        finish(Outcome::Ready, path, m_targetVersion);
    else if (outcome == UpdatePreparationApplicationService::Outcome::Cancelled)
        finish(Outcome::Cancelled, {}, m_targetVersion, error);
    else
        finish(Outcome::Rejected, {}, m_targetVersion, error);
}

void UpdateCheckApplicationService::finish(
        Outcome outcome, QString path, QString targetVersion, QString error) {
    m_request = {};
    m_targetVersion.clear();
    emit finished(outcome, path, targetVersion, error);
}
