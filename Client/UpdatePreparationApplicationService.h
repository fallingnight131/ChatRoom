#pragma once

#include "UpdateInstallerTrustVerifier.h"
#include "UpdateInstallerDownloadTransport.h"
#include "UpdateManifestApplicationService.h"

#include <QObject>
#include <functional>

class QNetworkAccessManager;
template<typename T> class QFutureWatcher;

class UpdatePreparationApplicationService : public QObject {
    Q_OBJECT
public:
    using TrustFunction = std::function<UpdateInstallerTrustVerifier::Result(
        const QString &, qint64, const QByteArray &, const QByteArray &)>;

    enum class Outcome {
        Ready,
        Rejected,
        Cancelled
    };

    struct StartResult {
        bool downloadStarted = false;
        UpdateManifestDecisionPolicy::Decision decision;
    };

    UpdatePreparationApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QObject *parent = nullptr);
    UpdatePreparationApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory,
        QString stagingDirectory,
        QNetworkAccessManager *networkManager,
        TrustFunction trustFunction,
        QObject *parent = nullptr);
    ~UpdatePreparationApplicationService() override;

    StartResult prepare(
        const UpdateManifestApplicationService::Request &request);
    void cancel();
    bool isActive() const;

signals:
    void progress(qint64 received, qint64 expected);
    void finished(UpdatePreparationApplicationService::Outcome outcome,
                  const QString &verifiedInstallerPath,
                  const QString &error);

private:
    void handleDownload(UpdateInstallerDownloadTransport::Outcome outcome,
                        const QString &path, const QString &error);
    void handleTrustFinished();

    UpdateManifestApplicationService m_manifestService;
    QString m_stagingDirectory;
    UpdateInstallerDownloadTransport *m_download = nullptr;
    QFutureWatcher<UpdateInstallerTrustVerifier::Result> *m_trustWatcher = nullptr;
    TrustFunction m_trustFunction;
    UpdateManifestDecisionPolicy::Decision m_activeDecision;
    QString m_verifyingPath;
    bool m_cancelRequested = false;
};
