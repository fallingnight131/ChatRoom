#include "UpdatePreparationApplicationService.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDebug>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTemporaryDir>
#include <QThread>
#include <QTimer>
#include <sodium.h>

#include <atomic>
#include <cstring>
#include <utility>

namespace {
using Service = UpdatePreparationApplicationService;
using Trust = UpdateInstallerTrustVerifier;

class PayloadReply final : public QNetworkReply {
public:
    PayloadReply(const QNetworkRequest &request,
                 QNetworkAccessManager::Operation operation,
                 QByteArray payload, QObject *parent)
        : QNetworkReply(parent), m_payload(std::move(payload)) {
        setRequest(request);
        setUrl(request.url());
        setOperation(operation);
        setAttribute(QNetworkRequest::HttpStatusCodeAttribute, 200);
        setHeader(QNetworkRequest::ContentLengthHeader, m_payload.size());
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        QTimer::singleShot(0, this, [this]() {
            if (m_done) return;
            emit readyRead();
            if (m_done) return;
            m_done = true;
            setFinished(true);
            emit finished();
        });
    }

    void abort() override {
        if (m_done) return;
        m_done = true;
        setError(OperationCanceledError, QStringLiteral("cancelled"));
        setFinished(true);
        emit finished();
    }

    qint64 bytesAvailable() const override {
        return (m_payload.size() - m_offset) + QNetworkReply::bytesAvailable();
    }

protected:
    qint64 readData(char *data, qint64 maxSize) override {
        if (m_offset >= m_payload.size()) return -1;
        const qint64 count = qMin<qint64>(maxSize, m_payload.size() - m_offset);
        memcpy(data, m_payload.constData() + m_offset, static_cast<size_t>(count));
        m_offset += count;
        return count;
    }

private:
    QByteArray m_payload;
    qsizetype m_offset = 0;
    bool m_done = false;
};

class PayloadManager final : public QNetworkAccessManager {
public:
    explicit PayloadManager(QByteArray payload) : m_payload(std::move(payload)) {}
    int requests = 0;

protected:
    QNetworkReply *createRequest(Operation operation,
                                 const QNetworkRequest &request,
                                 QIODevice *) override {
        ++requests;
        return new PayloadReply(request, operation, m_payload, this);
    }

private:
    QByteArray m_payload;
};

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdatePreparationApplicationServiceTest]" << message;
    return condition;
}

QByteArray quoted(const QString &value) {
    const auto encoded = QJsonDocument(QJsonArray{value}).toJson(QJsonDocument::Compact);
    return encoded.mid(1, encoded.size() - 2);
}

QByteArray canonical(const QJsonValue &value) {
    if (value.isBool()) return value.toBool() ? "true" : "false";
    if (value.isString()) return quoted(value.toString());
    if (value.isDouble()) return QByteArray::number(static_cast<qint64>(value.toDouble()));
    if (value.isArray()) {
        QByteArray result("[");
        const auto array = value.toArray();
        for (qsizetype index = 0; index < array.size(); ++index) {
            if (index) result += ',';
            result += canonical(array.at(index));
        }
        return result + ']';
    }
    QByteArray result("{");
    const auto object = value.toObject();
    const QStringList keys = object.keys();
    for (qsizetype index = 0; index < keys.size(); ++index) {
        if (index) result += ',';
        result += quoted(keys.at(index)) + ':' + canonical(object.value(keys.at(index)));
    }
    return result + '}';
}

UpdateManifestApplicationService::Request signedRequest(
        const QByteArray &payload, const QByteArray &secretKey,
        int rolloutPercentage = 100) {
    const QString digest = QString::fromLatin1(
        QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex());
    const QJsonObject manifest{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("product"), QStringLiteral("chat-room-windows-client")},
        {QStringLiteral("architecture"), QStringLiteral("x86_64")},
        {QStringLiteral("channel"), QStringLiteral("stable")},
        {QStringLiteral("manifestSequence"), 42},
        {QStringLiteral("signingKeyId"), QStringLiteral("test-key")},
        {QStringLiteral("publishedAt"), QStringLiteral("2026-08-12T00:00:00Z")},
        {QStringLiteral("expiresAt"), QStringLiteral("2026-08-19T00:00:00Z")},
        {QStringLiteral("version"), QStringLiteral("1.2.3")},
        {QStringLiteral("minimumUpdatableVersion"), QStringLiteral("1.0.0")},
        {QStringLiteral("sourceRevision"), QString(40, QLatin1Char('a'))},
        {QStringLiteral("rollout"), QJsonObject{
             {QStringLiteral("percentage"), rolloutPercentage},
             {QStringLiteral("seed"), QString(64, QLatin1Char('b'))}}},
        {QStringLiteral("installer"), QJsonObject{
             {QStringLiteral("url"), QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")},
             {QStringLiteral("size"), payload.size()},
             {QStringLiteral("sha256"), digest},
             {QStringLiteral("authenticodeSha256Thumbprint"), QString(64, QLatin1Char('d'))}}}
    };
    UpdateManifestApplicationService::Request request;
    request.canonicalManifest = canonical(manifest) + '\n';
    request.signature.resize(crypto_sign_BYTES);
    crypto_sign_detached(
        reinterpret_cast<unsigned char *>(request.signature.data()), nullptr,
        reinterpret_cast<const unsigned char *>(request.canonicalManifest.constData()),
        static_cast<unsigned long long>(request.canonicalManifest.size()),
        reinterpret_cast<const unsigned char *>(secretKey.constData()));
    request.currentVersion = QStringLiteral("1.1.0");
    request.channel = QStringLiteral("stable");
    request.nowUtc = QDateTime::fromString(QStringLiteral("2026-08-15T12:00:00Z"), Qt::ISODate);
    return request;
}

struct Completion {
    Service::Outcome outcome = Service::Outcome::Rejected;
    Service::PreparedInstaller installer;
    QString error;
};

Completion waitFor(Service &service) {
    Completion completion;
    QEventLoop loop;
    QObject::connect(&service, &Service::finished, &loop,
                     [&](Service::Outcome outcome,
                         const Service::PreparedInstaller &installer,
                         const QString &error) {
        completion = {outcome, installer, error};
        loop.quit();
    });
    QTimer::singleShot(3000, &loop, &QEventLoop::quit);
    loop.exec();
    return completion;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 1;
    QByteArray publicKey(crypto_sign_PUBLICKEYBYTES, Qt::Uninitialized);
    QByteArray secretKey(crypto_sign_SECRETKEYBYTES, Qt::Uninitialized);
    crypto_sign_keypair(reinterpret_cast<unsigned char *>(publicKey.data()),
                        reinterpret_cast<unsigned char *>(secretKey.data()));
    const QByteArray payload("trusted installer fixture");

    QTemporaryDir root;
    PayloadManager manager(payload);
    std::atomic_bool background{false};
    Service service({{QStringLiteral("test-key"), publicKey}},
                    root.filePath(QStringLiteral("state")),
                    root.filePath(QStringLiteral("stage")), &manager,
                    [&](const QString &path, qint64 size, const QByteArray &digest,
                        const QByteArray &thumbprint) {
        background = QThread::currentThread() != app.thread();
        const auto integrity = Trust::verifyIntegrity(path, size, digest);
        return integrity.valid && thumbprint == QByteArray(32, static_cast<char>(0xdd))
            ? Trust::Result{Trust::Outcome::Verified, {}}
            : Trust::Result{Trust::Outcome::IntegrityRejected,
                            QStringLiteral("fixture trust rejected")};
    });
    const auto start = service.prepare(signedRequest(payload, secretKey));
    if (!check(start.downloadStarted, start.decision.error)
            || !check(!service.prepare(signedRequest(payload, secretKey)).downloadStarted,
                      QStringLiteral("parallel preparation was allowed"))) return 1;
    const auto ready = waitFor(service);
    if (!check(ready.outcome == Service::Outcome::Ready, ready.error)
            || !check(background.load(), QStringLiteral("trust verification blocked application thread"))
            || !check(ready.installer.isComplete()
                          && ready.installer.path.endsWith(QStringLiteral(".exe"))
                          && ready.installer.size == payload.size()
                          && ready.installer.sha256 == QCryptographicHash::hash(
                              payload, QCryptographicHash::Sha256)
                          && QFileInfo::exists(ready.installer.path),
                      QStringLiteral("verified installer evidence is missing"))) return 1;
    QFile::remove(ready.installer.path);

    QTemporaryDir deferredRoot;
    PayloadManager deferredManager(payload);
    Service deferred({{QStringLiteral("test-key"), publicKey}},
                     deferredRoot.filePath(QStringLiteral("state")),
                     deferredRoot.filePath(QStringLiteral("stage")), &deferredManager,
                     [](const QString &, qint64, const QByteArray &, const QByteArray &) {
        return Trust::Result{Trust::Outcome::Verified, {}};
    });
    const auto noDownload = deferred.prepare(signedRequest(payload, secretKey, 0));
    if (!check(!noDownload.downloadStarted
                   && noDownload.decision.outcome
                       == UpdateManifestDecisionPolicy::Outcome::DeferredByRollout
                   && deferredManager.requests == 0,
               QStringLiteral("deferred rollout started a download"))) return 1;

    QTemporaryDir rejectedRoot;
    PayloadManager rejectedManager(payload);
    Service rejected({{QStringLiteral("test-key"), publicKey}},
                     rejectedRoot.filePath(QStringLiteral("state")),
                     rejectedRoot.filePath(QStringLiteral("stage")), &rejectedManager,
                     [](const QString &, qint64, const QByteArray &, const QByteArray &) {
        return Trust::Result{Trust::Outcome::AuthenticodeRejected,
                             QStringLiteral("publisher rejected")};
    });
    if (!rejected.prepare(signedRequest(payload, secretKey)).downloadStarted) return 1;
    const auto failed = waitFor(rejected);
    if (!check(failed.outcome == Service::Outcome::Rejected
                   && failed.installer.path.isEmpty()
                   && failed.error == QStringLiteral("publisher rejected")
                   && QDir(rejectedRoot.filePath(QStringLiteral("stage"))).entryList(
                          QDir::Files | QDir::NoDotAndDotDot).isEmpty(),
               QStringLiteral("trust rejection retained installer bytes"))) return 1;

    qInfo() << "[UpdatePreparationApplicationServiceTest] PASS";
    return 0;
}
