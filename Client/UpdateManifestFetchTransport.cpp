#include "UpdateManifestFetchTransport.h"

#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>

namespace {
constexpr qsizetype MaxManifestBytes = 64 * 1024;
constexpr qsizetype SignatureBytes = 64;
constexpr int FetchTimeoutMs = 15 * 1000;

bool safeUrl(const QUrl &url) {
    const QString path = url.path();
    const QByteArray encodedPath = url.path(QUrl::FullyEncoded).toUtf8();
    const QStringList segments = path.split('/', Qt::KeepEmptyParts);
    return url.isValid() && url.scheme() == QStringLiteral("https")
        && !url.host().isEmpty() && url.port() != 0 && url.userInfo().isEmpty()
        && !url.hasQuery() && !url.hasFragment()
        && !path.contains(QStringLiteral("//")) && !path.contains('\\')
        && !encodedPath.contains('%') && !segments.contains(QStringLiteral("."))
        && !segments.contains(QStringLiteral(".."));
}
}

UpdateManifestFetchTransport::UpdateManifestFetchTransport(QObject *parent)
    : UpdateManifestFetchTransport(new QNetworkAccessManager, parent) {
    m_manager->setParent(this);
}

UpdateManifestFetchTransport::UpdateManifestFetchTransport(
        QNetworkAccessManager *manager, QObject *parent)
    : QObject(parent), m_manager(manager) {}

UpdateManifestFetchTransport::~UpdateManifestFetchTransport() {
    if (m_reply) {
        disconnect(m_reply, nullptr, this, nullptr);
        m_reply->abort();
        m_reply = nullptr;
    }
}

void UpdateManifestFetchTransport::fail(QString *error, const QString &message) {
    if (error) *error = message;
}

bool UpdateManifestFetchTransport::validateRequest(
        const Request &request, QString *error) const {
    QUrl expectedSignature = request.manifestUrl;
    expectedSignature.setPath(request.manifestUrl.path() + QStringLiteral(".sig"));
    if (!m_manager || isActive() || !safeUrl(request.manifestUrl)
            || !safeUrl(request.signatureUrl)
            || request.manifestUrl.fileName() != QStringLiteral("manifest.json")
            || request.signatureUrl != expectedSignature) {
        fail(error, QStringLiteral("update manifest fetch request is invalid"));
        return false;
    }
    return true;
}

bool UpdateManifestFetchTransport::start(
        const Request &request, QString *error) {
    if (error) error->clear();
    if (!validateRequest(request, error)) return false;
    m_signatureUrl = request.signatureUrl;
    m_manifest.clear();
    m_current.clear();
    m_failure.clear();
    m_cancelled = false;
    startRequest(request.manifestUrl, Phase::Manifest);
    if (!m_reply) {
        m_phase = Phase::Idle;
        fail(error, QStringLiteral("update manifest HTTPS request could not be created"));
        return false;
    }
    return true;
}

void UpdateManifestFetchTransport::startRequest(const QUrl &url, Phase phase) {
    QNetworkRequest request(url);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::ManualRedirectPolicy);
    request.setTransferTimeout(FetchTimeoutMs);
    request.setRawHeader("Accept", phase == Phase::Manifest
        ? "application/json" : "application/octet-stream");
    request.setRawHeader("Cache-Control", "no-store");
    request.setRawHeader("Accept-Encoding", "identity");
    m_phase = phase;
    m_current.clear();
    m_reply = m_manager->get(request);
    if (!m_reply) return;
    m_reply->setReadBufferSize(16 * 1024);
    connect(m_reply, &QIODevice::readyRead, this,
            &UpdateManifestFetchTransport::consumeAvailable);
    connect(m_reply, &QNetworkReply::finished, this,
            &UpdateManifestFetchTransport::completeRequest);
}

bool UpdateManifestFetchTransport::isActive() const {
    return m_phase != Phase::Idle;
}

void UpdateManifestFetchTransport::cancel() {
    if (!m_reply) return;
    m_cancelled = true;
    m_reply->abort();
}

void UpdateManifestFetchTransport::consumeAvailable() {
    if (!m_reply || !m_failure.isEmpty()) return;
    const QByteArray bytes = m_reply->readAll();
    const qsizetype limit = m_phase == Phase::Manifest
        ? MaxManifestBytes : SignatureBytes;
    if (bytes.size() > limit - m_current.size()) {
        m_failure = m_phase == Phase::Manifest
            ? QStringLiteral("update manifest exceeds 64 KiB")
            : QStringLiteral("update signature exceeds 64 bytes");
        m_reply->abort();
        return;
    }
    m_current += bytes;
}

void UpdateManifestFetchTransport::completeRequest() {
    if (!m_reply) return;
    consumeAvailable();
    QNetworkReply *reply = m_reply;
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const bool redirected = reply->attribute(
        QNetworkRequest::RedirectionTargetAttribute).isValid();
    const QVariant contentLengthHeader = reply->header(QNetworkRequest::ContentLengthHeader);
    const qint64 contentLength = contentLengthHeader.isValid()
        ? contentLengthHeader.toLongLong() : -1;
    if (!m_cancelled && m_failure.isEmpty()) {
        if (reply->error() != QNetworkReply::NoError)
            m_failure = QStringLiteral("update manifest HTTPS request failed: %1")
                .arg(reply->errorString());
        else if (status != 200 || redirected)
            m_failure = QStringLiteral("update manifest server response is not an exact 200");
        else if (m_phase == Phase::Manifest
                 && contentLengthHeader.isValid()
                 && (contentLength <= 0 || contentLength > MaxManifestBytes))
            m_failure = QStringLiteral("update manifest Content-Length is invalid");
        else if (m_phase == Phase::Signature
                 && contentLengthHeader.isValid()
                 && contentLength != SignatureBytes)
            m_failure = QStringLiteral("update signature Content-Length is invalid");
        else if (m_phase == Phase::Manifest && m_current.isEmpty())
            m_failure = QStringLiteral("update manifest response is empty");
        else if (m_phase == Phase::Signature && m_current.size() != SignatureBytes)
            m_failure = QStringLiteral("update signature is not exactly 64 bytes");
    }
    reply->deleteLater();
    m_reply = nullptr;
    if (m_cancelled) {
        finish(Outcome::Cancelled, QStringLiteral("update manifest fetch cancelled"));
        return;
    }
    if (!m_failure.isEmpty()) {
        finish(Outcome::Rejected, m_failure);
        return;
    }
    if (m_phase == Phase::Manifest) {
        m_manifest = m_current;
        startRequest(m_signatureUrl, Phase::Signature);
        if (!m_reply) finish(Outcome::Rejected,
                            QStringLiteral("update signature HTTPS request could not be created"));
        return;
    }
    const QByteArray signature = m_current;
    m_phase = Phase::Idle;
    emit finished(Outcome::Succeeded, m_manifest, signature, {});
    m_manifest.clear();
    m_current.clear();
}

void UpdateManifestFetchTransport::finish(
        Outcome outcome, QString error) {
    m_phase = Phase::Idle;
    m_signatureUrl.clear();
    m_manifest.clear();
    m_current.clear();
    m_failure.clear();
    m_cancelled = false;
    emit finished(outcome, {}, {}, error);
}
