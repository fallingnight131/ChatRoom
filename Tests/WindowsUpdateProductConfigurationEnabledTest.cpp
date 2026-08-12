#include "WindowsUpdateProductConfiguration.h"

#include <QCoreApplication>
#include <QDebug>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const auto configured = WindowsUpdateProductConfiguration::fromBuild();
    if (!configured.enabled || !configured.error.isEmpty()
            || configured.channel != QStringLiteral("stable")
            || configured.manifestUrl.toString()
                != QStringLiteral("https://updates.example.test/windows/stable/manifest.json")
            || configured.trustedKeys.size() != 1
            || configured.trustedKeys.value(
                QStringLiteral("windows-update-2026-01")).size() != 32) {
        qCritical().noquote()
            << "[WindowsUpdateProductConfigurationEnabledTest]"
            << (configured.error.isEmpty()
                    ? QStringLiteral("compiled product configuration was not enabled")
                    : configured.error);
        return 1;
    }
    qInfo() << "[WindowsUpdateProductConfigurationEnabledTest] PASS";
    return 0;
}
