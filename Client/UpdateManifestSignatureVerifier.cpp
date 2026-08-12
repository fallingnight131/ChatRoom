#include "UpdateManifestSignatureVerifier.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QJsonValue>
#include <sodium.h>

#include <algorithm>
#include <cmath>

namespace {
QByteArray quotedJsonString(const QString &value) {
    const QByteArray encoded = QJsonDocument(QJsonArray{value}).toJson(QJsonDocument::Compact);
    return encoded.mid(1, encoded.size() - 2);
}
}

UpdateManifestSignatureVerifier::UpdateManifestSignatureVerifier(TrustedKeys trustedKeys)
    : m_trustedKeys(std::move(trustedKeys)) {}

void UpdateManifestSignatureVerifier::fail(QString *error, const QString &message) {
    if (error) *error = message;
}

QByteArray UpdateManifestSignatureVerifier::canonicalJson(const QJsonValue &value, bool *ok) {
    if (!ok || !*ok) return {};
    if (value.isNull()) return "null";
    if (value.isBool()) return value.toBool() ? "true" : "false";
    if (value.isString()) return quotedJsonString(value.toString());
    if (value.isDouble()) {
        const double number = value.toDouble();
        if (!std::isfinite(number) || std::floor(number) != number
                || std::abs(number) > 9007199254740991.0) {
            *ok = false;
            return {};
        }
        return QByteArray::number(static_cast<qint64>(number));
    }
    if (value.isArray()) {
        QByteArray result("[");
        const auto array = value.toArray();
        for (qsizetype index = 0; index < array.size(); ++index) {
            if (index) result += ',';
            result += canonicalJson(array.at(index), ok);
        }
        result += ']';
        return result;
    }
    if (value.isObject()) {
        QByteArray result("{");
        const auto object = value.toObject();
        QStringList keys = object.keys();
        std::sort(keys.begin(), keys.end());
        for (qsizetype index = 0; index < keys.size(); ++index) {
            if (index) result += ',';
            result += quotedJsonString(keys.at(index));
            result += ':';
            result += canonicalJson(object.value(keys.at(index)), ok);
        }
        result += '}';
        return result;
    }
    *ok = false;
    return {};
}

bool UpdateManifestSignatureVerifier::verify(const QByteArray &canonicalManifest,
                                             const QByteArray &signature,
                                             QJsonObject *verifiedManifest,
                                             QString *error) const {
    if (verifiedManifest) *verifiedManifest = {};
    if (error) error->clear();
    if (canonicalManifest.isEmpty() || canonicalManifest.size() > 64 * 1024
            || signature.size() != crypto_sign_BYTES) {
        fail(error, QStringLiteral("update manifest or signature size is invalid"));
        return false;
    }

    QJsonParseError parseError;
    const auto document = QJsonDocument::fromJson(canonicalManifest, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        fail(error, QStringLiteral("update manifest JSON is invalid"));
        return false;
    }
    const auto object = document.object();
    if (object.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
            || object.value(QStringLiteral("product")).toString()
                != QStringLiteral("chat-room-windows-client")) {
        fail(error, QStringLiteral("update manifest identity is invalid"));
        return false;
    }

    bool canonical = true;
    QByteArray expected = canonicalJson(object, &canonical);
    expected += '\n';
    if (!canonical || expected != canonicalManifest) {
        fail(error, QStringLiteral("update manifest JSON is not canonical"));
        return false;
    }

    const QString keyId = object.value(QStringLiteral("signingKeyId")).toString();
    const QByteArray publicKey = m_trustedKeys.value(keyId);
    if (keyId.isEmpty() || publicKey.size() != crypto_sign_PUBLICKEYBYTES) {
        fail(error, QStringLiteral("update signing key is not trusted"));
        return false;
    }
    if (sodium_init() < 0 || crypto_sign_verify_detached(
            reinterpret_cast<const unsigned char *>(signature.constData()),
            reinterpret_cast<const unsigned char *>(canonicalManifest.constData()),
            static_cast<unsigned long long>(canonicalManifest.size()),
            reinterpret_cast<const unsigned char *>(publicKey.constData())) != 0) {
        fail(error, QStringLiteral("update manifest signature is invalid"));
        return false;
    }
    if (verifiedManifest) *verifiedManifest = object;
    return true;
}
