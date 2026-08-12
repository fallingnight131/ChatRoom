#include "WindowsUpdateProductConfiguration.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsUpdateProductConfigurationTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    using Configuration = WindowsUpdateProductConfiguration;
    const Configuration::Key primary{
        QStringLiteral("windows-update-2026-01"), QString(64, QLatin1Char('a'))};
    const Configuration::Key secondary{
        QStringLiteral("windows-update-2027-01"), QString(64, QLatin1Char('b'))};
    const auto valid = Configuration::validate(
        QStringLiteral("stable"),
        QStringLiteral("https://updates.example.test/windows/stable/manifest.json"),
        {primary, secondary});
    if (!check(valid.enabled && valid.error.isEmpty()
                   && valid.channel == QStringLiteral("stable")
                   && valid.signatureUrl.toString()
                       == QStringLiteral("https://updates.example.test/windows/stable/manifest.json.sig")
                   && valid.trustedKeys.size() == 2
                   && valid.trustedKeys.value(primary.id).size() == 32,
               valid.error.isEmpty() ? QStringLiteral("valid configuration was lost")
                                     : valid.error)
            || !check(!Configuration::fromBuild().enabled
                          && Configuration::fromBuild().error.isEmpty(),
                      QStringLiteral("default build enabled product updates"))) return 1;

    const QList<Configuration::Value> rejected{
        Configuration::validate(QStringLiteral("preview"),
                                QStringLiteral("https://updates.example.test/windows/stable/manifest.json"),
                                {primary}),
        Configuration::validate(QStringLiteral("stable"),
                                QStringLiteral("http://updates.example.test/windows/stable/manifest.json"),
                                {primary}),
        Configuration::validate(QStringLiteral("stable"),
                                QStringLiteral("https://updates.example.test/windows/beta/manifest.json"),
                                {primary}),
        Configuration::validate(QStringLiteral("stable"),
                                QStringLiteral("https://updates.example.test/windows/stable/manifest.json?token=x"),
                                {primary}),
        Configuration::validate(QStringLiteral("stable"),
                                QStringLiteral("https://updates.example.test/windows/stable/manifest.json"),
                                {{QStringLiteral("UPPER"), QString(64, QLatin1Char('a'))}}),
        Configuration::validate(QStringLiteral("stable"),
                                QStringLiteral("https://updates.example.test/windows/stable/manifest.json"),
                                {primary, primary})
    };
    for (const auto &value : rejected) {
        if (!check(!value.enabled && !value.error.isEmpty(),
                   QStringLiteral("unsafe update configuration was enabled"))) return 1;
    }

    qInfo() << "[WindowsUpdateProductConfigurationTest] PASS";
    return 0;
}
