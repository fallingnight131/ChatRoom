#include "UpdateManifestSignatureVerifier.h"

#include <QCoreApplication>
#include <QDebug>
#include <sodium.h>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateManifestSignatureVerifierTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    if (!check(sodium_init() >= 0, QStringLiteral("libsodium initialization failed"))) return 1;

    QByteArray publicKey(crypto_sign_PUBLICKEYBYTES, Qt::Uninitialized);
    QByteArray secretKey(crypto_sign_SECRETKEYBYTES, Qt::Uninitialized);
    crypto_sign_keypair(
        reinterpret_cast<unsigned char *>(publicKey.data()),
        reinterpret_cast<unsigned char *>(secretKey.data()));

    const QByteArray manifest =
        "{\"product\":\"chat-room-windows-client\",\"schemaVersion\":1,"
        "\"signingKeyId\":\"test-2026-01\"}\n";
    QByteArray signature(crypto_sign_BYTES, Qt::Uninitialized);
    crypto_sign_detached(
        reinterpret_cast<unsigned char *>(signature.data()), nullptr,
        reinterpret_cast<const unsigned char *>(manifest.constData()),
        static_cast<unsigned long long>(manifest.size()),
        reinterpret_cast<const unsigned char *>(secretKey.constData()));
    sodium_memzero(secretKey.data(), static_cast<size_t>(secretKey.size()));

    UpdateManifestSignatureVerifier verifier({{QStringLiteral("test-2026-01"), publicKey}});
    QJsonObject verified;
    QString error;
    if (!check(verifier.verify(manifest, signature, &verified, &error), error)
            || !check(verified.value(QStringLiteral("signingKeyId")).toString()
                          == QStringLiteral("test-2026-01"),
                      QStringLiteral("verified key ID was not returned"))) return 1;

    UpdateManifestSignatureVerifier emptyVerifier;
    if (!check(!emptyVerifier.verify(manifest, signature, nullptr, &error)
                   && error.contains(QStringLiteral("not trusted")),
               QStringLiteral("empty key ring accepted a manifest"))) return 1;

    QByteArray alteredSignature = signature;
    alteredSignature[0] = static_cast<char>(alteredSignature[0] ^ 0x01);
    if (!check(!verifier.verify(manifest, alteredSignature, nullptr, &error),
               QStringLiteral("altered signature was accepted"))) return 1;

    const QByteArray nonCanonical =
        "{ \"product\": \"chat-room-windows-client\", \"schemaVersion\": 1, "
        "\"signingKeyId\": \"test-2026-01\" }\n";
    if (!check(!verifier.verify(nonCanonical, signature, nullptr, &error)
                   && error.contains(QStringLiteral("canonical")),
               QStringLiteral("non-canonical JSON was accepted"))) return 1;

    const QByteArray unknownKey =
        "{\"product\":\"chat-room-windows-client\",\"schemaVersion\":1,"
        "\"signingKeyId\":\"unknown\"}\n";
    QByteArray unknownSignature(crypto_sign_BYTES, Qt::Uninitialized);
    if (!check(!verifier.verify(unknownKey, unknownSignature, nullptr, &error)
                   && error.contains(QStringLiteral("not trusted")),
               QStringLiteral("unknown signing key was accepted"))) return 1;

    qInfo() << "[UpdateManifestSignatureVerifierTest] PASS";
    return 0;
}
