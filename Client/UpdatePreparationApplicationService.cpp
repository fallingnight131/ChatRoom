#include "UpdatePreparationApplicationService.h"

#include "UpdateInstallerDownloadTransport.h"

#include <QFile>
#include <QFutureWatcher>
#include <QNetworkAccessManager>
#include <QtConcurrent/QtConcurrentRun>

#include <utility>

UpdatePreparationApplicationService::UpdatePreparationApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QObject *parent)
    : QObject(parent),
      m_manifestService(std::move(trustedKeys), std::move(stateDirectory)),
      m_stagingDirectory(std::move(stagingDirectory)),
      m_download(new UpdateInstallerDownloadTransport(this)),
      m_trustWatcher(new QFutureWatcher<UpdateInstallerTrustVerifier::Result>(this)),
      m_trustFunction(&UpdateInstallerTrustVerifier::verify) {
    connect(m_download, &UpdateInstallerDownloadTransport::progress,
            this, &UpdatePreparationApplicationService::progress);
    connect(m_download, &UpdateInstallerDownloadTransport::finished, this,
            [this](UpdateInstallerDownloadTransport::Outcome outcome,
                   const QString &path, const QString &error) {
        handleDownload(outcome, path, error);
    });
    connect(m_trustWatcher, &QFutureWatcherBase::finished, this,
            &UpdatePreparationApplicationService::handleTrustFinished);
}

UpdatePreparationApplicationService::UpdatePreparationApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QNetworkAccessManager *networkManager,
        TrustFunction trustFunction,
        QObject *parent)
    : QObject(parent),
      m_manifestService(std::move(trustedKeys), std::move(stateDirectory)),
      m_stagingDirectory(std::move(stagingDirectory)),
      m_download(new UpdateInstallerDownloadTransport(networkManager, this)),
      m_trustWatcher(new QFutureWatcher<UpdateInstallerTrustVerifier::Result>(this)),
      m_trustFunction(std::move(trustFunction)) {
    connect(m_download, &UpdateInstallerDownloadTransport::progress,
            this, &UpdatePreparationApplicationService::progress);
    connect(m_download, &UpdateInstallerDownloadTransport::finished, this,
            [this](UpdateInstallerDownloadTransport::Outcome outcome,
                   const QString &path, const QString &error) {
        handleDownload(outcome, path, error);
    });
    connect(m_trustWatcher, &QFutureWatcherBase::finished, this,
            &UpdatePreparationApplicationService::handleTrustFinished);
}

UpdatePreparationApplicationService::~UpdatePreparationApplicationService() {
    disconnect(m_download, nullptr, this, nullptr);
    m_download->cancel();
    disconnect(m_trustWatcher, nullptr, this, nullptr);
    if (m_trustWatcher->isRunning()) m_trustWatcher->waitForFinished();
    if (!m_verifyingPath.isEmpty()) QFile::remove(m_verifyingPath);
}

UpdatePreparationApplicationService::StartResult
UpdatePreparationApplicationService::prepare(
        const UpdateManifestApplicationService::Request &request) {
    StartResult start;
    if (isActive()) {
        start.decision.error = QStringLiteral("update preparation is already active");
        return start;
    }
    m_cancelRequested = false;
    const auto accepted = m_manifestService.evaluateAndAccept(request);
    start.decision = accepted.decision;
    if (start.decision.outcome != UpdateManifestDecisionPolicy::Outcome::Eligible)
        return start;
    if (!m_trustFunction) {
        start.decision.outcome = UpdateManifestDecisionPolicy::Outcome::Rejected;
        start.decision.error = QStringLiteral("update installer trust verifier is unavailable");
        return start;
    }

    UpdateInstallerDownloadTransport::Request downloadRequest{
        QUrl(start.decision.installerUrl),
        start.decision.installerSize,
        m_stagingDirectory
    };
    QString error;
    if (!m_download->start(downloadRequest, &error)) {
        start.decision.outcome = UpdateManifestDecisionPolicy::Outcome::Rejected;
        start.decision.error = error;
        return start;
    }
    m_activeDecision = start.decision;
    start.downloadStarted = true;
    return start;
}

void UpdatePreparationApplicationService::cancel() {
    if (m_download->isActive()) m_download->cancel();
    else if (m_trustWatcher->isRunning() || !m_verifyingPath.isEmpty())
        m_cancelRequested = true;
}

bool UpdatePreparationApplicationService::isActive() const {
    return (m_download && m_download->isActive())
        || (m_trustWatcher && m_trustWatcher->isRunning())
        || !m_verifyingPath.isEmpty();
}

void UpdatePreparationApplicationService::handleDownload(
        UpdateInstallerDownloadTransport::Outcome downloadOutcome,
        const QString &path, const QString &error) {
    if (downloadOutcome == UpdateInstallerDownloadTransport::Outcome::Cancelled) {
        m_activeDecision = {};
        emit finished(Outcome::Cancelled, {}, error);
        return;
    }
    if (downloadOutcome != UpdateInstallerDownloadTransport::Outcome::Succeeded) {
        m_activeDecision = {};
        emit finished(Outcome::Rejected, {}, error);
        return;
    }

    m_verifyingPath = path;
    const qint64 size = m_activeDecision.installerSize;
    const QByteArray digest = m_activeDecision.installerSha256;
    const QByteArray thumbprint = m_activeDecision.authenticodeSha256Thumbprint;
    const TrustFunction trustFunction = m_trustFunction;
    m_activeDecision = {};
    m_trustWatcher->setFuture(QtConcurrent::run(
        [trustFunction, path, size, digest, thumbprint]() {
            try {
                return trustFunction(path, size, digest, thumbprint);
            } catch (...) {
                return UpdateInstallerTrustVerifier::Result{
                    UpdateInstallerTrustVerifier::Outcome::AuthenticodeRejected,
                    QStringLiteral("update installer trust verification failed")};
            }
        }));
}

void UpdatePreparationApplicationService::handleTrustFinished() {
    const auto trust = m_trustWatcher->result();
    const QString path = m_verifyingPath;
    m_verifyingPath.clear();
    if (m_cancelRequested) {
        QFile::remove(path);
        m_cancelRequested = false;
        emit finished(Outcome::Cancelled, {}, QStringLiteral("update verification cancelled"));
        return;
    }
    if (trust.outcome != UpdateInstallerTrustVerifier::Outcome::Verified) {
        QFile::remove(path);
        emit finished(Outcome::Rejected, {}, trust.error.isEmpty()
            ? QStringLiteral("update installer trust verification rejected the file")
            : trust.error);
        return;
    }
    emit finished(Outcome::Ready, path, {});
}
