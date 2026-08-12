#pragma once

#include <QObject>
#include <QUrl>

class QNetworkAccessManager;
class QNetworkReply;

class UpdateManifestFetchTransport : public QObject {
    Q_OBJECT
public:
    enum class Outcome {
        Succeeded,
        Rejected,
        Cancelled
    };

    struct Request {
        QUrl manifestUrl;
        QUrl signatureUrl;
    };

    explicit UpdateManifestFetchTransport(QObject *parent = nullptr);
    explicit UpdateManifestFetchTransport(QNetworkAccessManager *manager,
                                          QObject *parent = nullptr);
    ~UpdateManifestFetchTransport() override;

    bool start(const Request &request, QString *error = nullptr);
    void cancel();
    bool isActive() const;

signals:
    void finished(UpdateManifestFetchTransport::Outcome outcome,
                  const QByteArray &manifestBytes,
                  const QByteArray &signature,
                  const QString &error);

private:
    enum class Phase { Idle, Manifest, Signature };

    bool validateRequest(const Request &request, QString *error) const;
    void startRequest(const QUrl &url, Phase phase);
    void consumeAvailable();
    void completeRequest();
    void finish(Outcome outcome, QString error = {});
    static void fail(QString *error, const QString &message);

    QNetworkAccessManager *m_manager = nullptr;
    QNetworkReply *m_reply = nullptr;
    QUrl m_signatureUrl;
    QByteArray m_manifest;
    QByteArray m_current;
    Phase m_phase = Phase::Idle;
    bool m_cancelled = false;
    QString m_failure;
};
