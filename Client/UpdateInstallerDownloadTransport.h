#pragma once

#include <QObject>
#include <QString>
#include <QUrl>

class QNetworkAccessManager;
class QNetworkReply;
class QTemporaryFile;

class UpdateInstallerDownloadTransport : public QObject {
    Q_OBJECT
public:
    enum class Outcome {
        Succeeded,
        Rejected,
        Cancelled
    };

    struct Request {
        QUrl url;
        qint64 expectedSize = 0;
        QString stagingDirectory;
    };

    explicit UpdateInstallerDownloadTransport(QObject *parent = nullptr);
    explicit UpdateInstallerDownloadTransport(QNetworkAccessManager *manager,
                                               QObject *parent = nullptr);
    ~UpdateInstallerDownloadTransport() override;

    bool start(const Request &request, QString *error = nullptr);
    void cancel();
    bool isActive() const;

signals:
    void progress(qint64 received, qint64 expected);
    void finished(UpdateInstallerDownloadTransport::Outcome outcome,
                  const QString &temporaryPath,
                  const QString &error);

private:
    bool validateRequest(const Request &request, QString *error) const;
    void consumeAvailable();
    void complete();
    void cleanup();
    static void fail(QString *error, const QString &message);

    QNetworkAccessManager *m_manager = nullptr;
    QNetworkReply *m_reply = nullptr;
    QTemporaryFile *m_file = nullptr;
    qint64 m_expectedSize = 0;
    qint64 m_receivedSize = 0;
    QString m_failure;
    bool m_cancelled = false;
};
