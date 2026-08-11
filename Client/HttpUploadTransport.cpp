#include "HttpUploadTransport.h"

#include <QFile>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrl>
#include <QUrlQuery>

HttpUploadTransport::HttpUploadTransport(QObject *parent)
    : QObject(parent),
      m_manager(new QNetworkAccessManager(this)) {}

void HttpUploadTransport::configure(const QString &host, quint16 port,
                                    const QString &token, bool useTls) {
    m_host = host;
    m_port = port;
    m_token = token;
    m_useTls = useTls;
}

bool HttpUploadTransport::isConfigured() const {
    return !m_host.isEmpty() && m_port > 0 && !m_token.isEmpty();
}

bool HttpUploadTransport::upload(const QString &uploadId,
                                 const QString &uploadPath,
                                 const QString &filePath) {
    if (!isConfigured() || uploadId.isEmpty() || uploadPath.isEmpty() ||
        m_transfers.contains(uploadId)) {
        return false;
    }

    auto *file = new QFile(filePath, this);
    if (!file->open(QIODevice::ReadOnly)) {
        file->deleteLater();
        return false;
    }

    QUrl url;
    url.setScheme(m_useTls ? QStringLiteral("https") : QStringLiteral("http"));
    url.setHost(m_host);
    url.setPort(m_port);
    url.setPath(uploadPath);
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("token"), m_token);
    url.setQuery(query);

    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader,
                      QStringLiteral("application/octet-stream"));
    request.setHeader(QNetworkRequest::ContentLengthHeader,
                      QFileInfo(*file).size());
    QNetworkReply *reply = m_manager->put(request, file);
    m_transfers.insert(uploadId, {reply, file});

    connect(reply, &QNetworkReply::uploadProgress, this,
            [this, uploadId](qint64 sent, qint64 total) {
                emit progress(uploadId, sent, total);
            });
    connect(reply, &QNetworkReply::finished, this, [this, uploadId]() {
        const Transfer transfer = m_transfers.take(uploadId);
        if (!transfer.reply) return;
        const int status = transfer.reply->attribute(
            QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const bool success = transfer.reply->error() == QNetworkReply::NoError &&
                             status == 204;
        const QString error = success
                                  ? QString()
                                  : (status > 0
                                         ? QStringLiteral("HTTP %1").arg(status)
                                         : transfer.reply->errorString());
        if (transfer.file) {
            transfer.file->close();
            transfer.file->deleteLater();
        }
        transfer.reply->deleteLater();
        emit finished(uploadId, success, error);
    });
    return true;
}

void HttpUploadTransport::cancel(const QString &uploadId) {
    const auto it = m_transfers.find(uploadId);
    if (it != m_transfers.end() && it->reply)
        it->reply->abort();
}

void HttpUploadTransport::reset() {
    const QStringList uploadIds = m_transfers.keys();
    for (const QString &uploadId : uploadIds)
        cancel(uploadId);
    m_token.clear();
    m_host.clear();
    m_port = 0;
}
