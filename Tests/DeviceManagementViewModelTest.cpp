#include "DeviceManagementViewModel.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
int failures = 0;
void check(bool condition, const QString &message) {
    if (!condition) { ++failures; qCritical().noquote() << message; }
}
DeviceManagementViewModel::Device device(
        const QString &id, bool current,
        DeviceManagementViewModel::Platform platform = DeviceManagementViewModel::Platform::Web) {
    return {id, platform, 1, 2, current};
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QString listRequest;
    QString revokeRequest;
    QString revokedTarget;
    int listSequence = 0;
    DeviceManagementViewModel model(
        [&] { listRequest = QStringLiteral("list-%1").arg(++listSequence); return listRequest; },
        [&](const QString &target) {
            revokedTarget = target; revokeRequest = QStringLiteral("revoke-1"); return revokeRequest;
        });
    const QString current = QStringLiteral("30000000-0000-4000-8000-000000000001");
    const QString target = QStringLiteral("30000000-0000-4000-8000-000000000002");
    check(!model.refresh(), QStringLiteral("unauthenticated refresh must fail"));
    model.setAuthenticated(true, current);
    check(model.refresh() && model.loading(), QStringLiteral("authenticated refresh must start"));
    model.applyDirectory(QStringLiteral("stale"), {device(current, true)});
    check(model.loading(), QStringLiteral("stale response must not complete request"));
    model.applyDirectory(listRequest, {device(current, true),
                                       device(target, false, DeviceManagementViewModel::Platform::Windows)});
    check(!model.loading() && model.devices().size() == 2,
          QStringLiteral("valid directory must replace projection"));
    check(!model.revoke(current), QStringLiteral("current device must be protected"));
    check(model.revoke(target) && revokedTarget == target,
          QStringLiteral("known other device must start revoke"));
    model.applyRevoked(QStringLiteral("stale"), target);
    check(model.revokingDeviceId() == target, QStringLiteral("stale revoke must be ignored"));
    model.applyRevoked(revokeRequest, target);
    check(model.revokingDeviceId().isEmpty() && model.loading(),
          QStringLiteral("accepted revoke must remove target and requery"));
    check(model.devices().size() == 1, QStringLiteral("target must disappear immediately"));
    model.setAuthenticated(false);
    check(!model.loading() && model.revokingDeviceId().isEmpty(),
          QStringLiteral("disconnect must abandon ambiguous requests"));

    model.setAuthenticated(true, current);
    check(model.refresh(), QStringLiteral("resume must permit a fresh query"));
    model.applyDirectory(listRequest, {device(current, true), device(current, false)});
    check(model.failure() == DeviceManagementViewModel::Failure::InvalidDirectory
              && model.devices().isEmpty(),
          QStringLiteral("invalid duplicate directory must not replace safe projection"));
    check(model.refresh(), QStringLiteral("invalid directory must remain retryable"));
    model.applyProtocolError(listRequest);
    check(!model.loading()
              && model.failure() == DeviceManagementViewModel::Failure::LoadFailed,
          QStringLiteral("protocol denial must use a generic retryable UI state"));

    if (failures) return 1;
    qInfo() << "[DeviceManagementViewModelTest] PASS";
    return 0;
}
