#pragma once

#include <QHash>
#include <QObject>
#include <QString>

class QNetworkAccessManager;
class QNetworkReply;
class QTemporaryFile;

class HttpDownloadTransport : public QObject {
    Q_OBJECT
public:
    explicit HttpDownloadTransport(QObject *parent = nullptr);
    ~HttpDownloadTransport() override;

    void configure(const QString &host, quint16 port,
                   const QString &token, bool useTls);
    bool download(int fileId);
    void cancel(int fileId);
    void reset();
    bool isConfigured() const;

signals:
    void progress(int fileId, qint64 received, qint64 total);
    void finished(int fileId, bool success, const QString &temporaryPath,
                  const QString &error);

private:
    struct Transfer {
        QNetworkReply *reply = nullptr;
        QTemporaryFile *file = nullptr;
        bool writeFailed = false;
    };

    QNetworkAccessManager *m_manager = nullptr;
    QHash<int, Transfer> m_transfers;
    QString m_host;
    quint16 m_port = 0;
    QString m_token;
    bool m_useTls = false;
};
