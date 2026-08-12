#include "WindowsUpdateProductConfiguration.h"

#include <QRegularExpression>
#include <QSet>

namespace {
const QRegularExpression KeyId(QStringLiteral(
    R"(^[a-z0-9][a-z0-9.-]{0,63}$)"));
const QRegularExpression Hex64(QStringLiteral(R"(^[0-9a-f]{64}$)"));

WindowsUpdateProductConfiguration::Value disabled(const QString &error = {}) {
    WindowsUpdateProductConfiguration::Value value;
    value.error = error;
    return value;
}
}

WindowsUpdateProductConfiguration::Value
WindowsUpdateProductConfiguration::validate(
        const QString &channel, const QString &manifestUrl,
        const QList<Key> &keys) {
    if ((channel != QStringLiteral("stable")
            && channel != QStringLiteral("beta"))
            || keys.isEmpty() || keys.size() > 2) {
        return disabled(QStringLiteral("Windows update channel or key count is invalid"));
    }
    const QUrl manifest(manifestUrl, QUrl::StrictMode);
    const QStringList segments = manifest.path().split(
        QLatin1Char('/'), Qt::SkipEmptyParts);
    if (!manifest.isValid() || manifest.scheme() != QStringLiteral("https")
            || manifest.host().isEmpty() || !manifest.userInfo().isEmpty()
            || manifest.hasQuery() || manifest.hasFragment()
            || manifest.fileName() != QStringLiteral("manifest.json")
            || segments.size() < 2 || segments.at(segments.size() - 2) != channel
            || manifest.path().contains(QStringLiteral("//"))
            || segments.contains(QStringLiteral(".."))
            || manifest.toEncoded() != manifestUrl.toUtf8()) {
        return disabled(QStringLiteral("Windows update manifest URL is invalid"));
    }

    UpdateManifestSignatureVerifier::TrustedKeys trusted;
    for (const Key &key : keys) {
        if (!KeyId.match(key.id).hasMatch()
                || !Hex64.match(key.publicKeyHex).hasMatch()
                || trusted.contains(key.id)) {
            return disabled(QStringLiteral("Windows update public key is invalid"));
        }
        trusted.insert(key.id, QByteArray::fromHex(key.publicKeyHex.toLatin1()));
    }
    QUrl signature = manifest;
    signature.setPath(manifest.path() + QStringLiteral(".sig"));
    Value value;
    value.enabled = true;
    value.channel = channel;
    value.manifestUrl = manifest;
    value.signatureUrl = signature;
    value.trustedKeys = trusted;
    return value;
}

WindowsUpdateProductConfiguration::Value
WindowsUpdateProductConfiguration::fromBuild() {
#ifndef CHAT_UPDATE_CONFIGURATION_ENABLED
    return disabled();
#elif !defined(CHAT_UPDATE_CHANNEL) \
    || !defined(CHAT_UPDATE_MANIFEST_URL) \
    || !defined(CHAT_UPDATE_PRIMARY_KEY_ID) \
    || !defined(CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX)
    return disabled(QStringLiteral("Windows update build configuration is incomplete"));
#else
    QList<Key> keys{
        {QStringLiteral(CHAT_UPDATE_PRIMARY_KEY_ID),
         QStringLiteral(CHAT_UPDATE_PRIMARY_PUBLIC_KEY_HEX)}
    };
#if defined(CHAT_UPDATE_SECONDARY_KEY_ID) \
    && defined(CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX)
    keys.append({QStringLiteral(CHAT_UPDATE_SECONDARY_KEY_ID),
                 QStringLiteral(CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX)});
#elif defined(CHAT_UPDATE_SECONDARY_KEY_ID) \
    || defined(CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX)
    return disabled(QStringLiteral("Windows update secondary key is incomplete"));
#endif
    return validate(QStringLiteral(CHAT_UPDATE_CHANNEL),
                    QStringLiteral(CHAT_UPDATE_MANIFEST_URL), keys);
#endif
}
