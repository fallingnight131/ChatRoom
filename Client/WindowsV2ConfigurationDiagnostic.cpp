#include "WindowsV2ConfigurationDiagnostic.h"

#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>

QByteArray WindowsV2ConfigurationDiagnostic::canonicalJson(
        const WindowsV2ProductConfiguration::Value &configuration) {
    QJsonArray fallbackEndpoints;
    for (const QUrl &endpoint : configuration.fallbackEndpoints)
        fallbackEndpoints.push_back(endpoint.toString(QUrl::FullyEncoded));
    const QJsonObject value{
        {QStringLiteral("enabled"), configuration.enabled},
        {QStringLiteral("endpoint"), configuration.endpoint.toString(QUrl::FullyEncoded)},
        {QStringLiteral("fallbackEndpoints"), fallbackEndpoints},
        {QStringLiteral("messageForwardingEnabled"),
            configuration.messageForwardingEnabled},
        {QStringLiteral("messageSearchEnabled"), configuration.messageSearchEnabled},
        {QStringLiteral("notificationsEnabled"), configuration.notificationsEnabled},
        {QStringLiteral("accountBlockingEnabled"), configuration.accountBlockingEnabled},
        {QStringLiteral("schemaVersion"), 5},
    };
    return QJsonDocument(value).toJson(QJsonDocument::Compact) + '\n';
}
