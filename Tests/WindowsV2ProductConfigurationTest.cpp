#include "WindowsV2ProductConfiguration.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsV2ProductConfigurationTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    using Configuration = WindowsV2ProductConfiguration;
    const auto valid = Configuration::validate(
        QStringLiteral("wss://chat.example.test/v2/windows"),
        QStringLiteral("wss://chat-secondary.example.test/v2/windows"));
    if (!check(valid.enabled && valid.error.isEmpty()
                   && !valid.messageForwardingEnabled
                   && !valid.messageSearchEnabled
                   && !valid.notificationsEnabled
                   && valid.fallbackEndpoints.size() == 1
                   && valid.fallbackEndpoints.first().host()
                       == QStringLiteral("chat-secondary.example.test")
                   && valid.endpoint.host() == QStringLiteral("chat.example.test"),
               QStringLiteral("valid Windows V2 endpoint was rejected"))
            || !check(!Configuration::fromBuild().enabled
                          && !Configuration::fromBuild().messageForwardingEnabled
                          && !Configuration::fromBuild().messageSearchEnabled
                          && !Configuration::fromBuild().notificationsEnabled
                          && Configuration::fromBuild().error.isEmpty(),
                      QStringLiteral("default build enabled Windows V2"))) return 1;

    const QStringList rejected{
        QStringLiteral("ws://chat.example.test/v2/windows"),
        QStringLiteral("wss://user@chat.example.test/v2/windows"),
        QStringLiteral("wss://chat.example.test/v2/web"),
        QStringLiteral("wss://chat.example.test/v2/windows?token=secret"),
        QStringLiteral("wss://chat.example.test/v2/windows#fragment"),
        QStringLiteral("wss://chat.example.test:0/v2/windows"),
        QStringLiteral("wss://chat.example.test/v2/%77indows")
    };
    for (const QString &endpoint : rejected) {
        const auto value = Configuration::validate(endpoint);
        if (!check(!value.enabled && !value.error.isEmpty(),
                   QStringLiteral("unsafe Windows V2 endpoint was enabled: %1")
                       .arg(endpoint))) return 1;
    }
    if (!check(!Configuration::validate(
                    QStringLiteral("wss://chat.example.test/v2/windows"),
                    QStringLiteral("wss://chat.example.test/v2/windows")).enabled,
               QStringLiteral("duplicate Windows V2 fallback endpoint was enabled"))) return 1;

    qInfo() << "[WindowsV2ProductConfigurationTest] PASS";
    return 0;
}
