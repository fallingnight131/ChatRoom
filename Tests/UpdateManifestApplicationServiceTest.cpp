#include "UpdateManifestApplicationService.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTemporaryDir>
#include <sodium.h>

namespace {
using Service = UpdateManifestApplicationService;
using Outcome = UpdateManifestDecisionPolicy::Outcome;

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateManifestApplicationServiceTest]" << message;
    return condition;
}

QJsonObject manifest(int sequence = 42) {
    return {
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("product"), QStringLiteral("chat-room-windows-client")},
        {QStringLiteral("architecture"), QStringLiteral("x86_64")},
        {QStringLiteral("channel"), QStringLiteral("stable")},
        {QStringLiteral("manifestSequence"), sequence},
        {QStringLiteral("signingKeyId"), QStringLiteral("test-key")},
        {QStringLiteral("publishedAt"), QStringLiteral("2026-08-12T00:00:00Z")},
        {QStringLiteral("expiresAt"), QStringLiteral("2026-08-19T00:00:00Z")},
        {QStringLiteral("version"), QStringLiteral("1.2.3")},
        {QStringLiteral("minimumUpdatableVersion"), QStringLiteral("1.0.0")},
        {QStringLiteral("sourceRevision"), QString(40, QLatin1Char('a'))},
        {QStringLiteral("rollout"), QJsonObject{
             {QStringLiteral("percentage"), 100},
             {QStringLiteral("seed"), QString(64, QLatin1Char('b'))}}},
        {QStringLiteral("installer"), QJsonObject{
             {QStringLiteral("url"), QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")},
             {QStringLiteral("size"), 123456},
             {QStringLiteral("sha256"), QString(64, QLatin1Char('c'))},
             {QStringLiteral("authenticodeSha256Thumbprint"), QString(64, QLatin1Char('d'))}}}
    };
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

Service::Request request(const QJsonObject &value,
                         const QByteArray &secretKey) {
    Service::Request result;
    result.canonicalManifest = canonical(value) + '\n';
    result.signature.resize(crypto_sign_BYTES);
    crypto_sign_detached(
        reinterpret_cast<unsigned char *>(result.signature.data()), nullptr,
        reinterpret_cast<const unsigned char *>(result.canonicalManifest.constData()),
        static_cast<unsigned long long>(result.canonicalManifest.size()),
        reinterpret_cast<const unsigned char *>(secretKey.constData()));
    result.currentVersion = QStringLiteral("1.1.0");
    result.channel = QStringLiteral("stable");
    result.nowUtc = QDateTime::fromString(QStringLiteral("2026-08-15T12:00:00Z"), Qt::ISODate);
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
    QTemporaryDir root;
    if (!root.isValid()) return 1;

    Service service({{QStringLiteral("test-key"), publicKey}}, root.filePath(QStringLiteral("state")));
    auto signedRequest = request(manifest(), secretKey);
    auto result = service.evaluateAndAccept(signedRequest);
    if (!check(result.decision.outcome == Outcome::Eligible,
               result.decision.error)
            || !check(!result.stableDeviceId.isEmpty(),
                      QStringLiteral("application service omitted device identity"))) return 1;

    const auto retry = service.evaluateAndAccept(signedRequest);
    if (!check(retry.decision.outcome == Outcome::Eligible,
               QStringLiteral("identical signed request was not idempotent"))
            || !check(retry.stableDeviceId == result.stableDeviceId,
                      QStringLiteral("device identity changed during retry"))) return 1;

    auto olderRequest = request(manifest(41), secretKey);
    if (!check(service.evaluateAndAccept(olderRequest).decision.outcome == Outcome::Rejected,
               QStringLiteral("signed lower-sequence manifest bypassed repository state"))) return 1;

    signedRequest.signature[0] = static_cast<char>(signedRequest.signature.at(0) ^ 1);
    if (!check(service.evaluateAndAccept(signedRequest).decision.outcome == Outcome::Rejected,
               QStringLiteral("tampered signature reached decision policy"))) return 1;

    QTemporaryDir emptyRoot;
    Service noTrust({}, emptyRoot.filePath(QStringLiteral("state")));
    if (!check(noTrust.evaluateAndAccept(request(manifest(), secretKey)).decision.outcome
                   == Outcome::Rejected,
               QStringLiteral("empty trust ring accepted a manifest"))
            || !check(!QFileInfo::exists(emptyRoot.filePath(QStringLiteral("state"))),
                      QStringLiteral("untrusted manifest created durable state"))) return 1;

    qInfo() << "[UpdateManifestApplicationServiceTest] PASS";
    return 0;
}
