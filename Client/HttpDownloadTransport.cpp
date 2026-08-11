#include "HttpDownloadTransport.h"

#include <QDir>
#include <QFile>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTemporaryFile>
#include <QUrl>
#include <QUrlQuery>
#include <utility>

HttpDownloadTransport::HttpDownloadTransport(QObject *parent)
    : QObject(parent), m_manager(new QNetworkAccessManager(this)) {}

HttpDownloadTransport::~HttpDownloadTransport() {
    for (const Transfer &transfer : std::as_const(m_transfers)) {
        if (transfer.reply) transfer.reply->abort();
        if (transfer.file) {
            const QString path = transfer.file->fileName();
            transfer.file->close();
            QFile::remove(path);
        }
    }
}

void HttpDownloadTransport::configure(const QString &host, quint16 port,
                                      const QString &token, bool useTls) {
    m_host = host;
    m_port = port;
    m_token = token;
    m_useTls = useTls;
}

bool HttpDownloadTransport::isConfigured() const {
    return !m_host.isEmpty() && m_port > 0 && !m_token.isEmpty();
}

bool HttpDownloadTransport::download(int fileId) {
    if (!isConfigured() || fileId == 0 || m_transfers.contains(fileId))
        return false;

    auto *file = new QTemporaryFile(
        QDir::tempPath() + QStringLiteral("/chatroom-download-XXXXXX"), this);
    file->setAutoRemove(false);
    if (!file->open()) {
        file->deleteLater();
        return false;
    }

    QUrl url;
    url.setScheme(m_useTls ? QStringLiteral("https") : QStringLiteral("http"));
    url.setHost(m_host);
    url.setPort(m_port);
    url.setPath(QStringLiteral("/api/download/%1").arg(fileId));
    QUrlQuery query;
    query.addQueryItem(QStringLiteral("token"), m_token);
    query.addQueryItem(QStringLiteral("friend"), fileId < 0
        ? QStringLiteral("1") : QStringLiteral("0"));
    query.addQueryItem(QStringLiteral("disposition"), QStringLiteral("attachment"));
    url.setQuery(query);

    QNetworkRequest request(url);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *reply = m_manager->get(request);
    m_transfers.insert(fileId, {reply, file, false});

    connect(reply, &QNetworkReply::downloadProgress, this,
            [this, fileId](qint64 received, qint64 total) {
                emit progress(fileId, received, total);
            });
    connect(reply, &QIODevice::readyRead, this, [this, fileId]() {
        auto it = m_transfers.find(fileId);
        if (it == m_transfers.end() || !it->reply || !it->file) return;
        const QByteArray bytes = it->reply->readAll();
        if (it->file->write(bytes) != bytes.size()) it->writeFailed = true;
    });
    connect(reply, &QNetworkReply::finished, this, [this, fileId]() {
        const Transfer transfer = m_transfers.take(fileId);
        if (!transfer.reply || !transfer.file) return;
        const QByteArray remaining = transfer.reply->readAll();
        const bool tailWritten = transfer.file->write(remaining) == remaining.size();
        const int status = transfer.reply->attribute(
            QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const bool success = !transfer.writeFailed && tailWritten &&
                             transfer.reply->error() == QNetworkReply::NoError &&
                             status == 200;
        const QString path = transfer.file->fileName();
        transfer.file->flush();
        transfer.file->close();
        const QString error = success
            ? QString()
            : (status > 0 ? QStringLiteral("HTTP %1").arg(status)
                          : transfer.reply->errorString());
        if (!success) QFile::remove(path);
        transfer.file->deleteLater();
        transfer.reply->deleteLater();
        emit finished(fileId, success, success ? path : QString(), error);
    });
    return true;
}

void HttpDownloadTransport::cancel(int fileId) {
    const auto it = m_transfers.find(fileId);
    if (it != m_transfers.end() && it->reply) it->reply->abort();
}

void HttpDownloadTransport::reset() {
    const QList<int> fileIds = m_transfers.keys();
    for (int fileId : fileIds) cancel(fileId);
    m_token.clear();
    m_host.clear();
    m_port = 0;
}
