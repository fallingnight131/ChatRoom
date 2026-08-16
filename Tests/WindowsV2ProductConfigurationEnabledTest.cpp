#include "WindowsV2ProductConfiguration.h"

#include <QCoreApplication>
#include <QDebug>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const auto value = WindowsV2ProductConfiguration::fromBuild();
    if (!value.enabled || !value.messageForwardingEnabled
            || !value.messageSearchEnabled || !value.error.isEmpty()
            || !value.notificationsEnabled
            || value.endpoint.toString()
                != QStringLiteral("wss://chat.example.test/v2/windows")
            || value.fallbackEndpoints.size() != 1
            || value.fallbackEndpoints.first().toString()
                != QStringLiteral("wss://chat-secondary.example.test/v2/windows")) {
        qCritical().noquote()
            << "[WindowsV2ProductConfigurationEnabledTest] compiled endpoint was lost"
            << value.error;
        return 1;
    }
    qInfo() << "[WindowsV2ProductConfigurationEnabledTest] PASS";
    return 0;
}
