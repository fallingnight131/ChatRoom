#include "UpdateInstallerDownloadTransport.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTemporaryFile>

namespace {
constexpr qint64 MaxInstallerSize = 2LL * 1024 * 1024 * 1024;
constexpr int TransferTimeoutMs = 2 * 60 * 1000;
}

UpdateInstallerDownloadTransport::UpdateInstallerDownloadTransport(QObject *parent)
    : UpdateInstallerDownloadTransport(new QNetworkAccessManager, parent) {
    m_manager->setParent(this);
}

UpdateInstallerDownloadTransport::UpdateInstallerDownloadTransport(
        QNetworkAccessManager *manager, QObject *parent)
    : QObject(parent), m_manager(manager) {}

UpdateInstallerDownloadTransport::~UpdateInstallerDownloadTransport() {
    if (m_reply) {
        disconnect(m_reply, nullptr, this, nullptr);
        m_reply->abort();
        m_reply = nullptr;
    }
    cleanup();
}

void UpdateInstallerDownloadTransport::fail(QString *error, const QString &message) {
    if (error) *error = message;
}

bool UpdateInstallerDownloadTransport::validateRequest(
        const Request &request, QString *error) const {
    const QFileInfo directory(request.stagingDirectory);
    if (!m_manager || m_reply || !request.url.isValid()
            || request.url.scheme() != QStringLiteral("https")
            || request.url.host().isEmpty() || !request.url.userInfo().isEmpty()
            || request.url.hasQuery() || request.url.hasFragment()
            || request.url.fileName().isEmpty()
            || request.expectedSize <= 0 || request.expectedSize > MaxInstallerSize
            || request.stagingDirectory.isEmpty() || !directory.isAbsolute()
            || directory.isSymLink()) {
        fail(error, QStringLiteral("update download request is invalid"));
        return false;
    }
    return true;
}

bool UpdateInstallerDownloadTransport::start(
        const Request &request, QString *error) {
    if (error) error->clear();
    if (!validateRequest(request, error)) return false;
    if (!QDir().mkpath(request.stagingDirectory)) {
        fail(error, QStringLiteral("update staging directory cannot be created"));
        return false;
    }
    const QFileInfo directory(request.stagingDirectory);
    bool safeDirectory = directory.isDir() && !directory.isSymLink();
#ifndef Q_OS_WIN
    safeDirectory = safeDirectory
        && QFile::setPermissions(request.stagingDirectory,
                                 QFileDevice::ReadOwner | QFileDevice::WriteOwner
                                     | QFileDevice::ExeOwner);
#endif
    if (!safeDirectory) {
        fail(error, QStringLiteral("update staging directory is unsafe"));
        return false;
    }

    auto *file = new QTemporaryFile(
        QDir(request.stagingDirectory).filePath(
            QStringLiteral("installer-XXXXXX.exe.part")), this);
    file->setAutoRemove(false);
    bool fileReady = file->open();
#ifndef Q_OS_WIN
    fileReady = fileReady && file->setPermissions(
        QFileDevice::ReadOwner | QFileDevice::WriteOwner);
#endif
    if (!fileReady) {
        const QString path = file->fileName();
        file->close();
        if (!path.isEmpty()) QFile::remove(path);
        file->deleteLater();
        fail(error, QStringLiteral("update staging file cannot be created securely"));
        return false;
    }

    QNetworkRequest networkRequest(request.url);
    networkRequest.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                                QNetworkRequest::ManualRedirectPolicy);
    networkRequest.setTransferTimeout(TransferTimeoutMs);
    networkRequest.setRawHeader("Accept", "application/octet-stream");
    networkRequest.setRawHeader("Cache-Control", "no-store");
    networkRequest.setRawHeader("Accept-Encoding", "identity");

    m_expectedSize = request.expectedSize;
    m_receivedSize = 0;
    m_failure.clear();
    m_cancelled = false;
    m_file = file;
    m_reply = m_manager->get(networkRequest);
    if (!m_reply) {
        cleanup();
        fail(error, QStringLiteral("update HTTPS request could not be created"));
        return false;
    }
    m_reply->setReadBufferSize(256 * 1024);
    connect(m_reply, &QIODevice::readyRead, this,
            &UpdateInstallerDownloadTransport::consumeAvailable);
    connect(m_reply, &QNetworkReply::downloadProgress, this,
            [this](qint64 received, qint64) {
                emit progress(received, m_expectedSize);
            });
    connect(m_reply, &QNetworkReply::finished, this,
            &UpdateInstallerDownloadTransport::complete);
    return true;
}

bool UpdateInstallerDownloadTransport::isActive() const {
    return m_reply != nullptr;
}

void UpdateInstallerDownloadTransport::cancel() {
    if (!m_reply) return;
    m_cancelled = true;
    m_reply->abort();
}

void UpdateInstallerDownloadTransport::consumeAvailable() {
    if (!m_reply || !m_file || !m_failure.isEmpty()) return;
    const QByteArray bytes = m_reply->readAll();
    if (bytes.isEmpty()) return;
    if (m_receivedSize > m_expectedSize - bytes.size()) {
        m_failure = QStringLiteral("update response exceeds signed installer size");
        m_reply->abort();
        return;
    }
    if (m_file->write(bytes) != bytes.size()) {
        m_failure = QStringLiteral("update staging file write failed");
        m_reply->abort();
        return;
    }
    m_receivedSize += bytes.size();
}

void UpdateInstallerDownloadTransport::complete() {
    if (!m_reply || !m_file) return;
    consumeAvailable();
    const auto *reply = m_reply;
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QVariant contentLength = reply->header(QNetworkRequest::ContentLengthHeader);
    const bool redirected = reply->attribute(
        QNetworkRequest::RedirectionTargetAttribute).isValid();
    if (!m_cancelled && m_failure.isEmpty()) {
        if (reply->error() != QNetworkReply::NoError)
            m_failure = QStringLiteral("update HTTPS request failed: %1").arg(reply->errorString());
        else if (status != 200 || redirected)
            m_failure = QStringLiteral("update server response is not an exact 200");
        else if (contentLength.isValid()
                 && contentLength.toLongLong() != m_expectedSize)
            m_failure = QStringLiteral("update Content-Length does not match signed size");
        else if (m_receivedSize != m_expectedSize)
            m_failure = QStringLiteral("update response size does not match signed size");
        else if (!m_file->flush())
            m_failure = QStringLiteral("update staging file flush failed");
    }

    const QString path = m_file->fileName();
    m_file->close();
    const Outcome outcome = m_cancelled ? Outcome::Cancelled
        : (m_failure.isEmpty() ? Outcome::Succeeded : Outcome::Rejected);
    if (outcome != Outcome::Succeeded) QFile::remove(path);
    const QString emittedPath = outcome == Outcome::Succeeded ? path : QString();
    const QString emittedError = m_cancelled
        ? QStringLiteral("update download cancelled") : m_failure;
    m_reply->deleteLater();
    m_file->deleteLater();
    m_reply = nullptr;
    m_file = nullptr;
    m_expectedSize = 0;
    m_receivedSize = 0;
    emit finished(outcome, emittedPath, emittedError);
}

void UpdateInstallerDownloadTransport::cleanup() {
    if (m_file) {
        const QString path = m_file->fileName();
        m_file->close();
        QFile::remove(path);
        delete m_file;
        m_file = nullptr;
    }
    m_expectedSize = 0;
    m_receivedSize = 0;
}
