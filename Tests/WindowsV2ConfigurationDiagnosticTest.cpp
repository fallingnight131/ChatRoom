#include "WindowsV2ConfigurationDiagnostic.h"

#include <QCoreApplication>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    WindowsV2ProductConfiguration::Value value;
    value.enabled = true;
    value.messageForwardingEnabled = true;
    value.messageSearchEnabled = true;
    value.endpoint = QUrl(QStringLiteral("wss://preview-chat.example.test/v2/windows"));
    value.fallbackEndpoints = {
        QUrl(QStringLiteral("wss://preview-chat-secondary.example.test/v2/windows"))};
    const QByteArray encoded = WindowsV2ConfigurationDiagnostic::canonicalJson(value);
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(encoded, &error);
    const auto object = document.object();
    if (error.error != QJsonParseError::NoError || !document.isObject()
            || encoded != QJsonDocument(object).toJson(QJsonDocument::Compact) + '\n'
            || object.size() != 6 || object.value(QStringLiteral("schemaVersion")).toInt() != 3
            || !object.value(QStringLiteral("enabled")).toBool()
            || !object.value(QStringLiteral("messageForwardingEnabled")).toBool()
            || !object.value(QStringLiteral("messageSearchEnabled")).toBool()
            || object.value(QStringLiteral("endpoint")).toString()
                != QStringLiteral("wss://preview-chat.example.test/v2/windows")
            || object.value(QStringLiteral("fallbackEndpoints")).toArray()
                != QJsonArray{QStringLiteral("wss://preview-chat-secondary.example.test/v2/windows")}) {
        qCritical() << "Windows V2 configuration diagnostic was not canonical";
        return 1;
    }
    const auto disabledDocument = QJsonDocument::fromJson(
        WindowsV2ConfigurationDiagnostic::canonicalJson({}));
    const auto disabled = disabledDocument.object();
    if (disabled.size() != 6 || disabled.value(QStringLiteral("schemaVersion")).toInt() != 3
            || disabled.value(QStringLiteral("enabled")).toBool()
            || disabled.value(QStringLiteral("messageForwardingEnabled")).toBool()
            || disabled.value(QStringLiteral("messageSearchEnabled")).toBool()
            || !disabled.value(QStringLiteral("endpoint")).toString().isEmpty()
            || !disabled.value(QStringLiteral("fallbackEndpoints")).toArray().isEmpty()) {
        qCritical() << "disabled Windows V2 diagnostic exposed enabled state";
        return 1;
    }
    qInfo() << "[WindowsV2ConfigurationDiagnosticTest] PASS";
    return 0;
}
