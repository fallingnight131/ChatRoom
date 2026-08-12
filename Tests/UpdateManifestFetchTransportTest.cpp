#include "UpdateManifestFetchTransport.h"

#include <QCoreApplication>
#include <QDebug>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTimer>

#include <cstring>
#include <utility>

namespace {
using Transport = UpdateManifestFetchTransport;

struct Response {
    QByteArray payload;
    int status = 200;
    qint64 contentLength = -1;
    QUrl redirect;
    int delayMs = 0;
};

class FakeReply final : public QNetworkReply {
public:
    FakeReply(const QNetworkRequest &request,
              QNetworkAccessManager::Operation operation,
              Response response, QObject *parent)
        : QNetworkReply(parent), m_response(std::move(response)) {
        setRequest(request);
        setUrl(request.url());
        setOperation(operation);
        setAttribute(QNetworkRequest::HttpStatusCodeAttribute, m_response.status);
        if (m_response.contentLength >= 0)
            setHeader(QNetworkRequest::ContentLengthHeader, m_response.contentLength);
        if (!m_response.redirect.isEmpty())
            setAttribute(QNetworkRequest::RedirectionTargetAttribute, m_response.redirect);
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        QTimer::singleShot(m_response.delayMs, this, [this]() { deliver(); });
    }

    void abort() override {
        if (m_done) return;
        m_done = true;
        setError(OperationCanceledError, QStringLiteral("cancelled"));
        setFinished(true);
        emit finished();
    }

    qint64 bytesAvailable() const override {
        return (m_response.payload.size() - m_offset) + QNetworkReply::bytesAvailable();
    }

protected:
    qint64 readData(char *data, qint64 maxSize) override {
        if (m_offset >= m_response.payload.size()) return -1;
        const qint64 count = qMin<qint64>(maxSize, m_response.payload.size() - m_offset);
        memcpy(data, m_response.payload.constData() + m_offset, static_cast<size_t>(count));
        m_offset += count;
        return count;
    }

private:
    void deliver() {
        if (m_done) return;
        emit readyRead();
        if (m_done) return;
        m_done = true;
        setFinished(true);
        emit finished();
    }

    Response m_response;
    qsizetype m_offset = 0;
    bool m_done = false;
};

class FakeManager final : public QNetworkAccessManager {
public:
    explicit FakeManager(QList<Response> responses)
        : m_responses(std::move(responses)) {}

    QList<QNetworkRequest> observedRequests;

protected:
    QNetworkReply *createRequest(Operation operation,
                                 const QNetworkRequest &request,
                                 QIODevice *) override {
        observedRequests.append(request);
        if (m_responses.isEmpty()) return nullptr;
        const Response response = m_responses.takeFirst();
        return new FakeReply(request, operation, response, this);
    }

private:
    QList<Response> m_responses;
};

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateManifestFetchTransportTest]" << message;
    return condition;
}

struct Result {
    Transport::Outcome outcome = Transport::Outcome::Rejected;
    QByteArray manifest;
    QByteArray signature;
    QString error;
    QList<QNetworkRequest> requests;
};

Result run(QList<Response> responses, bool cancel = false) {
    FakeManager manager(std::move(responses));
    Transport transport(&manager);
    Result result;
    QEventLoop loop;
    QObject::connect(&transport, &Transport::finished, &loop,
                     [&](Transport::Outcome outcome, const QByteArray &manifest,
                         const QByteArray &signature, const QString &error) {
        result = {outcome, manifest, signature, error, manager.observedRequests};
        loop.quit();
    });
    const Transport::Request request{
        QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json")),
        QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json.sig"))};
    QString error;
    if (!transport.start(request, &error)) {
        result.error = error;
        return result;
    }
    if (cancel) QTimer::singleShot(0, &transport, &Transport::cancel);
    QTimer::singleShot(2000, &loop, &QEventLoop::quit);
    loop.exec();
    result.requests = manager.observedRequests;
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const QByteArray manifest("{\"schemaVersion\":1}\n");
    const QByteArray signature(64, '\x5a');

    for (const auto &request : {
             Transport::Request{QUrl(QStringLiteral("http://updates.example.test/stable/manifest.json")),
                                QUrl(QStringLiteral("http://updates.example.test/stable/manifest.json.sig"))},
             Transport::Request{QUrl(QStringLiteral("https://user@updates.example.test/stable/manifest.json")),
                                QUrl(QStringLiteral("https://user@updates.example.test/stable/manifest.json.sig"))},
             Transport::Request{QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json?token=x")),
                                QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json.sig"))},
             Transport::Request{QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json")),
                                QUrl(QStringLiteral("https://evil.example.test/stable/manifest.json.sig"))},
             Transport::Request{QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json")),
                                QUrl(QStringLiteral("https://updates.example.test/stable/other.sig"))},
             Transport::Request{QUrl(QStringLiteral("https://updates.example.test/%2e%2e/manifest.json")),
                                QUrl(QStringLiteral("https://updates.example.test/%2e%2e/manifest.json.sig"))}}) {
        FakeManager manager({{manifest, 200, manifest.size(), {}, 0}});
        Transport transport(&manager);
        QString error;
        if (!check(!transport.start(request, &error)
                       && !error.isEmpty() && manager.observedRequests.isEmpty(),
                   QStringLiteral("unsafe manifest request reached the network"))) return 1;
    }

    const Result success = run({{manifest, 200, manifest.size(), {}, 0},
                                {signature, 200, signature.size(), {}, 0}});
    if (!check(success.outcome == Transport::Outcome::Succeeded, success.error)
            || !check(success.manifest == manifest && success.signature == signature,
                      QStringLiteral("successful manifest bytes changed"))
            || !check(success.requests.size() == 2,
                      QStringLiteral("manifest and signature were not fetched sequentially"))
            || !check(success.requests.at(0).url().fileName() == QStringLiteral("manifest.json")
                          && success.requests.at(1).url().fileName() == QStringLiteral("manifest.json.sig"),
                      QStringLiteral("unexpected update manifest URLs were requested"))) return 1;
    for (const auto &request : success.requests) {
        if (!check(request.attribute(QNetworkRequest::RedirectPolicyAttribute).toInt()
                       == QNetworkRequest::ManualRedirectPolicy,
                   QStringLiteral("redirect policy is not fail closed"))
                || !check(request.rawHeader("Cache-Control") == "no-store",
                          QStringLiteral("cache bypass header changed"))
                || !check(request.rawHeader("Accept-Encoding") == "identity",
                          QStringLiteral("content encoding was not disabled"))
                || !check(request.transferTimeout() == 15000,
                          QStringLiteral("manifest fetch timeout changed"))) return 1;
    }

    const Result oversizedManifest = run({{QByteArray(64 * 1024 + 1, 'm'), 200, -1, {}, 0}});
    if (!check(oversizedManifest.outcome == Transport::Outcome::Rejected
                   && oversizedManifest.requests.size() == 1
                   && oversizedManifest.manifest.isEmpty(),
               QStringLiteral("oversized manifest was not rejected before signature fetch"))) return 1;

    const Result invalidManifestLength = run({{manifest, 200, 64 * 1024 + 1, {}, 0}});
    const Result shortSignature = run({{manifest, 200, manifest.size(), {}, 0},
                                       {QByteArray(63, 's'), 200, 63, {}, 0}});
    const Result redirect = run({{manifest, 302, manifest.size(),
                                  QUrl(QStringLiteral("https://evil.example.test/x")), 0}});
    const Result cancelled = run({{manifest, 200, manifest.size(), {}, 100}}, true);
    int failureIndex = 0;
    for (const auto &failure : {invalidManifestLength, shortSignature, redirect, cancelled}) {
        if (!check(failure.outcome != Transport::Outcome::Succeeded
                       && failure.manifest.isEmpty() && failure.signature.isEmpty()
                       && !failure.error.isEmpty(),
                   QStringLiteral("failed manifest fetch %1 exposed untrusted bytes: %2")
                       .arg(failureIndex).arg(failure.error))) return 1;
        ++failureIndex;
    }

    qInfo() << "[UpdateManifestFetchTransportTest] PASS";
    return 0;
}
