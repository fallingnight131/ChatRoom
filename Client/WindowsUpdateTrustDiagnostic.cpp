#include "WindowsUpdateTrustDiagnostic.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

QByteArray WindowsUpdateTrustDiagnostic::canonicalJson(
        const WindowsUpdateProductConfiguration::Value &configuration) {
    QJsonArray keys;
    for (auto iterator = configuration.trustedKeys.cbegin();
         iterator != configuration.trustedKeys.cend(); ++iterator) {
        keys.append(QJsonObject{
            {QStringLiteral("keyId"), iterator.key()},
            {QStringLiteral("publicKeyHex"),
             QString::fromLatin1(iterator.value().toHex())},
        });
    }
    const QJsonObject document{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("product"), QStringLiteral("chat-room-windows-client")},
        {QStringLiteral("enabled"), configuration.enabled},
        {QStringLiteral("channel"), configuration.channel},
        {QStringLiteral("manifestUrl"), configuration.manifestUrl.toString()},
        {QStringLiteral("signatureUrl"), configuration.signatureUrl.toString()},
        {QStringLiteral("trustedKeys"), keys},
        {QStringLiteral("error"), configuration.error},
    };
    return QJsonDocument(document).toJson(QJsonDocument::Compact) + '\n';
}
