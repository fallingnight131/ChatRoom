#pragma once

#include <QObject>
#include <QHash>
#include <QString>

class QFile;
class QNetworkAccessManager;
class QNetworkReply;

class HttpUploadTransport : public QObject {
    Q_OBJECT
public:
    explicit HttpUploadTransport(QObject *parent = nullptr);

    void configure(const QString &host, quint16 port,
                   const QString &token, bool useTls);
    bool upload(const QString &uploadId, const QString &uploadPath,
                const QString &filePath);
    void cancel(const QString &uploadId);
    void reset();
    bool isConfigured() const;

signals:
    void progress(const QString &uploadId, qint64 sent, qint64 total);
    void finished(const QString &uploadId, bool success, const QString &error);

private:
    struct Transfer {
        QNetworkReply *reply = nullptr;
        QFile *file = nullptr;
    };

    QNetworkAccessManager *m_manager = nullptr;
    QHash<QString, Transfer> m_transfers;
    QString m_host;
    quint16 m_port = 0;
    QString m_token;
    bool m_useTls = false;
};
