#include "DeviceManagementApplicationService.h"
#include "DeviceManagementViewModel.h"

#include <QCoreApplication>
#include <QDebug>
#include <QEventLoop>
#include <QTimer>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[DeviceManagementApplicationServiceTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    int listCalls = 0;
    DeviceManagementViewModel model(
        [&] { ++listCalls; return QStringLiteral("list-1"); },
        [](const QString &) { return QStringLiteral("revoke-1"); });
    int starts = 0;
    int stops = 0;
    int authentications = 0;
    QByteArray observedPassword;
    DeviceManagementApplicationService service(
        &model, QStringLiteral("user_01"), QByteArrayLiteral("secret"),
        [&] { ++starts; }, [&] { ++stops; },
        [&](const QString &username, QByteArray password) {
            if (username == QStringLiteral("user_01")) ++authentications;
            observedPassword = password;
            password.fill('\0');
        });
    if (!check(service.start() && starts == 1 && service.credentialAvailable(),
               QStringLiteral("start did not arm the one-use credential"))
            || !check(service.readyForAuthentication()
                          && authentications == 1
                          && observedPassword == QByteArrayLiteral("secret")
                          && !service.credentialAvailable(),
                      QStringLiteral("credential was not consumed exactly once"))
            || !check(!service.readyForAuthentication() && authentications == 1,
                      QStringLiteral("credential was reused"))) return 1;

    service.authenticated(QStringLiteral("device-current"));
    if (!check(model.authenticated() && model.loading() && listCalls == 1,
               QStringLiteral("authentication did not refresh the live directory"))) return 1;
    service.unavailable();
    if (!check(!model.authenticated() && !model.loading(),
               QStringLiteral("disconnect did not abandon live request state"))) return 1;
    service.stop();
    if (!check(stops == 1, QStringLiteral("stop command was not called exactly once"))) return 1;

    DeviceManagementViewModel expiryModel(
        [] { return QStringLiteral("list-2"); },
        [](const QString &) { return QStringLiteral("revoke-2"); });
    int expiryStops = 0;
    DeviceManagementApplicationService expiring(
        &expiryModel, QStringLiteral("user_02"), QByteArrayLiteral("temporary"),
        [] {}, [&] { ++expiryStops; }, [](const QString &, QByteArray) {}, 1);
    if (!expiring.start()) return 1;
    QEventLoop loop;
    QTimer::singleShot(20, &loop, &QEventLoop::quit);
    loop.exec();
    if (!check(!expiring.credentialAvailable() && expiryStops == 1
                   && !expiring.readyForAuthentication(),
               QStringLiteral("expired credential remained usable"))) return 1;

    qInfo() << "[DeviceManagementApplicationServiceTest] PASS";
    return 0;
}
