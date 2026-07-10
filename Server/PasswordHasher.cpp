#include "PasswordHasher.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QDebug>

#include <sodium.h>

namespace {

void clearBytes(QByteArray &bytes) {
    if (!bytes.isEmpty()) {
        sodium_memzero(bytes.data(), static_cast<size_t>(bytes.size()));
    }
}

} // namespace

bool PasswordHasher::ensureInitialized() {
    static const bool initialized = [] {
        const int result = sodium_init();
        if (result < 0) {
            qCritical() << "[Auth] libsodium initialization failed";
            return false;
        }
        return true;
    }();
    return initialized;
}

QString PasswordHasher::createHash(const QString &password) {
    if (!ensureInitialized()) return {};

    QByteArray passwordBytes = password.toUtf8();
    char output[crypto_pwhash_STRBYTES];
    const int result = crypto_pwhash_str(
        output,
        passwordBytes.constData(),
        static_cast<unsigned long long>(passwordBytes.size()),
        crypto_pwhash_OPSLIMIT_INTERACTIVE,
        crypto_pwhash_MEMLIMIT_INTERACTIVE);
    clearBytes(passwordBytes);

    if (result != 0) {
        sodium_memzero(output, sizeof output);
        qWarning() << "[Auth] Argon2id password hashing failed";
        return {};
    }

    const QString encoded = QString::fromLatin1(output);
    sodium_memzero(output, sizeof output);
    return encoded;
}

PasswordHasher::Verification PasswordHasher::verify(
    const QString &password,
    const QString &storedHash,
    const QString &legacySalt) {
    if (!ensureInitialized() || storedHash.isEmpty()) {
        return Verification::Failed;
    }

    if (!isModernHash(storedHash)) {
        return verifyLegacySha256(password, storedHash, legacySalt)
            ? Verification::ValidNeedsRehash
            : Verification::Failed;
    }

    QByteArray passwordBytes = password.toUtf8();
    const QByteArray encoded = storedHash.toLatin1();
    const int verified = crypto_pwhash_str_verify(
        encoded.constData(),
        passwordBytes.constData(),
        static_cast<unsigned long long>(passwordBytes.size()));
    clearBytes(passwordBytes);
    if (verified != 0) return Verification::Failed;

    const int needsRehash = crypto_pwhash_str_needs_rehash(
        encoded.constData(),
        crypto_pwhash_OPSLIMIT_INTERACTIVE,
        crypto_pwhash_MEMLIMIT_INTERACTIVE);
    return needsRehash == 1 ? Verification::ValidNeedsRehash : Verification::Valid;
}

bool PasswordHasher::isModernHash(const QString &storedHash) {
    return storedHash.startsWith(QStringLiteral("$argon2"));
}

bool PasswordHasher::verifyLegacySha256(
    const QString &password,
    const QString &storedHash,
    const QString &legacySalt) {
    const QByteArray expected = QByteArray::fromHex(storedHash.toLatin1());
    if (expected.size() != QCryptographicHash::hashLength(QCryptographicHash::Sha256)) {
        return false;
    }

    QByteArray material = (password + legacySalt).toUtf8();
    QByteArray actual = QCryptographicHash::hash(material, QCryptographicHash::Sha256);
    clearBytes(material);
    const bool matches = sodium_memcmp(
        actual.constData(), expected.constData(), static_cast<size_t>(actual.size())) == 0;
    clearBytes(actual);
    return matches;
}
