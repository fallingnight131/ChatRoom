#include "WindowsV2ConfigurationDiagnostic.h"

#include <QJsonDocument>
#include <QJsonObject>

QByteArray WindowsV2ConfigurationDiagnostic::canonicalJson(
        const WindowsV2ProductConfiguration::Value &configuration) {
    const QJsonObject value{
        {QStringLiteral("enabled"), configuration.enabled},
        {QStringLiteral("endpoint"), configuration.endpoint.toString(QUrl::FullyEncoded)},
        {QStringLiteral("messageForwardingEnabled"),
            configuration.messageForwardingEnabled},
        {QStringLiteral("schemaVersion"), 1},
    };
    return QJsonDocument(value).toJson(QJsonDocument::Compact) + '\n';
}
