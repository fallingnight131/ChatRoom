#include "UpdateInstallerDownloadTransport.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QTemporaryDir>
#include <QTimer>

#include <cstdio>
#include <cstring>
#include <utility>

namespace {
using Transport = UpdateInstallerDownloadTransport;

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
        emit downloadProgress(m_response.payload.size(), m_response.contentLength);
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
    explicit FakeManager(Response response)
        : m_response(std::move(response)) {}

    QNetworkRequest observedRequest;

protected:
    QNetworkReply *createRequest(Operation operation,
                                 const QNetworkRequest &request,
                                 QIODevice *) override {
        observedRequest = request;
        return new FakeReply(request, operation, m_response, this);
    }

private:
    Response m_response;
};

bool check(bool condition, const QString &message) {
    if (!condition) {
        const QByteArray bytes = message.toLocal8Bit();
        std::fprintf(stderr, "[UpdateInstallerDownloadTransportTest] %s\n", bytes.constData());
    }
    return condition;
}

struct Result {
    Transport::Outcome outcome = Transport::Outcome::Rejected;
    QString path;
    QString error;
    QNetworkRequest request;
};

Result run(const Response &response, const QString &directory,
           qint64 expectedSize, bool cancel = false) {
    FakeManager manager(response);
    Transport transport(&manager);
    Transport::Request request{
        QUrl(QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")),
        expectedSize,
        directory
    };
    Result result;
    QEventLoop loop;
    QObject::connect(&transport, &Transport::finished, &loop,
                     [&](Transport::Outcome outcome, const QString &path,
                         const QString &error) {
        result = {outcome, path, error, manager.observedRequest};
        loop.quit();
    });
    QString error;
    if (!transport.start(request, &error)) {
        result.error = error;
        return result;
    }
    if (cancel) QTimer::singleShot(0, &transport, &Transport::cancel);
    QTimer::singleShot(2000, &loop, &QEventLoop::quit);
    loop.exec();
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    if (!check(root.isValid(), QStringLiteral("temporary directory creation failed"))) return 1;
    const QByteArray payload("signed-installer-bytes");

    for (const auto &url : {
             QStringLiteral("http://updates.example.test/Setup.exe"),
             QStringLiteral("https://user@updates.example.test/Setup.exe"),
             QStringLiteral("https://updates.example.test/Setup.exe?token=secret")}) {
        FakeManager invalidManager({payload, 200, payload.size(), {}, 0});
        Transport invalid(&invalidManager);
        Transport::Request invalidRequest{
            QUrl(url), payload.size(), root.filePath(QStringLiteral("invalid"))};
        QString error;
        if (!check(!invalid.start(invalidRequest, &error),
                   QStringLiteral("unsafe update URL was accepted"))) return 1;
    }

    const QString successDir = root.filePath(QStringLiteral("success"));
    const auto success = run({payload, 200, payload.size(), {}, 0},
                             successDir, payload.size());
    QFile downloaded(success.path);
    if (!check(success.outcome == Transport::Outcome::Succeeded,
               success.error)
            || !check(downloaded.open(QIODevice::ReadOnly)
                          && downloaded.readAll() == payload,
                      QStringLiteral("successful staged bytes changed"))
            || !check(success.request.attribute(QNetworkRequest::RedirectPolicyAttribute).toInt()
                          == QNetworkRequest::ManualRedirectPolicy,
                      QStringLiteral("redirect policy is not fail closed"))
            || !check(success.request.rawHeader("Accept-Encoding") == "identity",
                      QStringLiteral("content encoding was not disabled"))
            || !check(success.request.transferTimeout() == 120000,
                      QStringLiteral("update transfer timeout changed"))) return 1;
    downloaded.close();
    QFile::remove(success.path);

    for (const auto &failure : {
             run({payload, 200, payload.size() + 1, {}, 0},
                 root.filePath(QStringLiteral("length")), payload.size()),
             run({payload + 'x', 200, -1, {}, 0},
                 root.filePath(QStringLiteral("oversize")), payload.size()),
             run({payload, 302, payload.size(), QUrl(QStringLiteral("https://evil.test/x"))},
                 root.filePath(QStringLiteral("redirect")), payload.size()),
             run({payload, 200, payload.size(), {}, 100},
                 root.filePath(QStringLiteral("cancel")), payload.size(), true)}) {
        if (!check(failure.outcome != Transport::Outcome::Succeeded
                       && failure.path.isEmpty() && !failure.error.isEmpty(),
                   QStringLiteral("failed/cancelled transfer retained a staged file"))) return 1;
    }
    for (const auto &name : {QStringLiteral("length"), QStringLiteral("oversize"),
                             QStringLiteral("redirect"), QStringLiteral("cancel")}) {
        if (!check(QDir(root.filePath(name)).entryList(QDir::Files | QDir::NoDotAndDotDot).isEmpty(),
                   QStringLiteral("failure directory contains a partial file"))) return 1;
    }

    const QString destroyedDirectory = root.filePath(QStringLiteral("destroyed"));
    FakeManager delayed({payload, 200, payload.size(), {}, 100});
    {
        Transport transport(&delayed);
        Transport::Request request{
            QUrl(QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")),
            payload.size(), destroyedDirectory};
        if (!check(transport.start(request),
                   QStringLiteral("destroyed transport fixture did not start"))) return 1;
    }
    if (!check(QDir(destroyedDirectory).entryList(
                       QDir::Files | QDir::NoDotAndDotDot).isEmpty(),
               QStringLiteral("destroyed transport retained a partial file"))) return 1;

    qInfo() << "[UpdateInstallerDownloadTransportTest] PASS";
    return 0;
}
