#include "UpdateCheckApplicationService.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDebug>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTemporaryDir>
#include <QTimer>
#include <sodium.h>

#include <cstring>
#include <utility>

namespace {
using Service = UpdateCheckApplicationService;
using Trust = UpdateInstallerTrustVerifier;

class Reply final : public QNetworkReply {
public:
    Reply(const QNetworkRequest &request,
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
        return m_payload.size() - m_offset + QNetworkReply::bytesAvailable();
    }

protected:
    qint64 readData(char *data, qint64 maximum) override {
        if (m_offset >= m_payload.size()) return -1;
        const qint64 count = qMin<qint64>(maximum, m_payload.size() - m_offset);
        memcpy(data, m_payload.constData() + m_offset, static_cast<size_t>(count));
        m_offset += count;
        return count;
    }

private:
    QByteArray m_payload;
    qsizetype m_offset = 0;
    bool m_done = false;
};

class Manager final : public QNetworkAccessManager {
public:
    explicit Manager(QList<QByteArray> responses)
        : m_responses(std::move(responses)) {}
    QList<QUrl> urls;

protected:
    QNetworkReply *createRequest(Operation operation,
                                 const QNetworkRequest &request,
                                 QIODevice *) override {
        urls.append(request.url());
        if (m_responses.isEmpty()) return nullptr;
        return new Reply(request, operation, m_responses.takeFirst(), this);
    }

private:
    QList<QByteArray> m_responses;
};

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateCheckApplicationServiceTest]" << message;
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
        for (qsizetype i = 0; i < array.size(); ++i) {
            if (i) result += ',';
            result += canonical(array.at(i));
        }
        return result + ']';
    }
    QByteArray result("{");
    const auto object = value.toObject();
    const QStringList keys = object.keys();
    for (qsizetype i = 0; i < keys.size(); ++i) {
        if (i) result += ',';
        result += quoted(keys.at(i)) + ':' + canonical(object.value(keys.at(i)));
    }
    return result + '}';
}

struct SignedManifest {
    QByteArray bytes;
    QByteArray signature;
};

SignedManifest makeManifest(const QByteArray &installer,
                            const QByteArray &secretKey,
                            int rollout = 100) {
    const QString digest = QString::fromLatin1(
        QCryptographicHash::hash(installer, QCryptographicHash::Sha256).toHex());
    const QJsonObject object{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("product"), QStringLiteral("chat-room-windows-client")},
        {QStringLiteral("architecture"), QStringLiteral("x86_64")},
        {QStringLiteral("channel"), QStringLiteral("stable")},
        {QStringLiteral("manifestSequence"), 43},
        {QStringLiteral("signingKeyId"), QStringLiteral("test-key")},
        {QStringLiteral("publishedAt"), QStringLiteral("2026-08-12T00:00:00Z")},
        {QStringLiteral("expiresAt"), QStringLiteral("2026-08-19T00:00:00Z")},
        {QStringLiteral("version"), QStringLiteral("1.2.3")},
        {QStringLiteral("minimumUpdatableVersion"), QStringLiteral("1.0.0")},
        {QStringLiteral("sourceRevision"), QString(40, QLatin1Char('a'))},
        {QStringLiteral("rollout"), QJsonObject{
             {QStringLiteral("percentage"), rollout},
             {QStringLiteral("seed"), QString(64, QLatin1Char('b'))}}},
        {QStringLiteral("installer"), QJsonObject{
             {QStringLiteral("url"), QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")},
             {QStringLiteral("size"), installer.size()},
             {QStringLiteral("sha256"), digest},
             {QStringLiteral("authenticodeSha256Thumbprint"), QString(64, QLatin1Char('d'))}}}
    };
    SignedManifest result;
    result.bytes = canonical(object) + '\n';
    result.signature.resize(crypto_sign_BYTES);
    crypto_sign_detached(
        reinterpret_cast<unsigned char *>(result.signature.data()), nullptr,
        reinterpret_cast<const unsigned char *>(result.bytes.constData()),
        static_cast<unsigned long long>(result.bytes.size()),
        reinterpret_cast<const unsigned char *>(secretKey.constData()));
    return result;
}

Service::Request request() {
    return {
        QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json")),
        QUrl(QStringLiteral("https://updates.example.test/stable/manifest.json.sig")),
        QStringLiteral("1.1.0"), QStringLiteral("stable"),
        QDateTime::fromString(QStringLiteral("2026-08-15T12:00:00Z"), Qt::ISODate)};
}

struct Completion {
    Service::Outcome outcome = Service::Outcome::Rejected;
    QString path;
    QString version;
    QString error;
};

Completion waitFor(Service &service) {
    Completion result;
    QEventLoop loop;
    QObject::connect(&service, &Service::finished, &loop,
                     [&](Service::Outcome outcome, const QString &path,
                         const QString &version, const QString &error) {
        result = {outcome, path, version, error};
        loop.quit();
    });
    QTimer::singleShot(3000, &loop, &QEventLoop::quit);
    loop.exec();
    return result;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    if (sodium_init() < 0) return 1;
    QByteArray publicKey(crypto_sign_PUBLICKEYBYTES, Qt::Uninitialized);
    QByteArray secretKey(crypto_sign_SECRETKEYBYTES, Qt::Uninitialized);
    crypto_sign_keypair(reinterpret_cast<unsigned char *>(publicKey.data()),
                        reinterpret_cast<unsigned char *>(secretKey.data()));
    const QByteArray installer("verified installer fixture");
    const auto signedManifest = makeManifest(installer, secretKey);

    QTemporaryDir root;
    Manager manager({signedManifest.bytes, signedManifest.signature, installer});
    Service service({{QStringLiteral("test-key"), publicKey}},
                    root.filePath(QStringLiteral("state")),
                    root.filePath(QStringLiteral("stage")), &manager,
                    [](const QString &path, qint64 size, const QByteArray &digest,
                       const QByteArray &) {
        return Trust::verifyIntegrity(path, size, digest).valid
            ? Trust::Result{Trust::Outcome::Verified, {}}
            : Trust::Result{Trust::Outcome::IntegrityRejected,
                            QStringLiteral("integrity rejected")};
    });
    QString error;
    if (!check(service.start(request(), &error), error)
            || !check(!service.start(request(), &error),
                      QStringLiteral("parallel update check was allowed"))) return 1;
    const Completion ready = waitFor(service);
    if (!check(ready.outcome == Service::Outcome::Ready, ready.error)
            || !check(ready.version == QStringLiteral("1.2.3")
                          && QFile::exists(ready.path),
                      QStringLiteral("verified update metadata was lost"))
            || !check(manager.urls.size() == 3
                          && manager.urls.at(0).fileName() == QStringLiteral("manifest.json")
                          && manager.urls.at(1).fileName() == QStringLiteral("manifest.json.sig")
                          && manager.urls.at(2).fileName().endsWith(QStringLiteral("Setup.exe")),
                      QStringLiteral("trust pipeline network order changed"))) return 1;
    QFile::remove(ready.path);

    QTemporaryDir rejectedRoot;
    QByteArray invalidSignature = signedManifest.signature;
    invalidSignature[0] ^= 1;
    Manager rejectedManager({signedManifest.bytes, invalidSignature, installer});
    Service rejected({{QStringLiteral("test-key"), publicKey}},
                     rejectedRoot.filePath(QStringLiteral("state")),
                     rejectedRoot.filePath(QStringLiteral("stage")), &rejectedManager,
                     [](const QString &, qint64, const QByteArray &, const QByteArray &) {
        return Trust::Result{Trust::Outcome::Verified, {}};
    });
    if (!rejected.start(request())) return 1;
    const Completion denied = waitFor(rejected);
    if (!check(denied.outcome == Service::Outcome::Rejected
                   && denied.path.isEmpty() && !denied.error.isEmpty()
                   && rejectedManager.urls.size() == 2
                   && QDir(rejectedRoot.filePath(QStringLiteral("stage"))).entryList(
                          QDir::Files | QDir::NoDotAndDotDot).isEmpty(),
               QStringLiteral("invalid signature reached installer download"))) return 1;

    QTemporaryDir deferredRoot;
    const auto deferredManifest = makeManifest(installer, secretKey, 0);
    Manager deferredManager({deferredManifest.bytes, deferredManifest.signature, installer});
    Service deferred({{QStringLiteral("test-key"), publicKey}},
                     deferredRoot.filePath(QStringLiteral("state")),
                     deferredRoot.filePath(QStringLiteral("stage")), &deferredManager,
                     [](const QString &, qint64, const QByteArray &, const QByteArray &) {
        return Trust::Result{Trust::Outcome::Verified, {}};
    });
    if (!deferred.start(request())) return 1;
    const Completion withheld = waitFor(deferred);
    if (!check(withheld.outcome == Service::Outcome::DeferredByRollout
                   && withheld.path.isEmpty() && deferredManager.urls.size() == 2,
               QStringLiteral("deferred rollout downloaded installer bytes"))) return 1;

    qInfo() << "[UpdateCheckApplicationServiceTest] PASS";
    return 0;
}
