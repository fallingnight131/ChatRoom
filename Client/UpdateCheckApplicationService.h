#pragma once

#include "UpdateManifestFetchTransport.h"
#include "UpdatePreparationApplicationService.h"

#include <QObject>

class QNetworkAccessManager;

class UpdateCheckApplicationService : public QObject {
    Q_OBJECT
public:
    enum class Outcome {
        Ready,
        NoUpdate,
        ManualUpdateRequired,
        DeferredByRollout,
        Rejected,
        Cancelled
    };

    struct Request {
        QUrl manifestUrl;
        QUrl signatureUrl;
        QString currentVersion;
        QString channel;
        QDateTime nowUtc;
    };

    UpdateCheckApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QObject *parent = nullptr);
    UpdateCheckApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QNetworkAccessManager *networkManager,
        UpdatePreparationApplicationService::TrustFunction trustFunction,
        QObject *parent = nullptr);

    bool start(const Request &request, QString *error = nullptr);
    void cancel();
    bool isActive() const;

signals:
    void progress(qint64 received, qint64 expected);
    void finished(UpdateCheckApplicationService::Outcome outcome,
                  const UpdatePreparationApplicationService::PreparedInstaller &installer,
                  const QString &targetVersion,
                  const QString &error);

private:
    void handleManifestFetch(UpdateManifestFetchTransport::Outcome outcome,
                             const QByteArray &manifestBytes,
                             const QByteArray &signature,
                             const QString &error);
    void handlePreparation(UpdatePreparationApplicationService::Outcome outcome,
                           const UpdatePreparationApplicationService::PreparedInstaller &installer,
                           const QString &error);
    void finish(Outcome outcome,
                UpdatePreparationApplicationService::PreparedInstaller installer = {},
                QString targetVersion = {}, QString error = {});

    UpdateManifestFetchTransport *m_fetch = nullptr;
    UpdatePreparationApplicationService *m_preparation = nullptr;
    Request m_request;
    QString m_targetVersion;
};
