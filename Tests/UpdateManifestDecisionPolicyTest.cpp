#include "UpdateManifestDecisionPolicy.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDebug>
#include <QJsonDocument>

namespace {
using Policy = UpdateManifestDecisionPolicy;

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateManifestDecisionPolicyTest]" << message;
    return condition;
}

QJsonObject manifest(int percentage = 100) {
    return {
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("product"), QStringLiteral("chat-room-windows-client")},
        {QStringLiteral("architecture"), QStringLiteral("x86_64")},
        {QStringLiteral("channel"), QStringLiteral("stable")},
        {QStringLiteral("manifestSequence"), 42},
        {QStringLiteral("signingKeyId"), QStringLiteral("windows-update-2026-01")},
        {QStringLiteral("publishedAt"), QStringLiteral("2026-08-12T00:00:00Z")},
        {QStringLiteral("expiresAt"), QStringLiteral("2026-08-19T00:00:00Z")},
        {QStringLiteral("version"), QStringLiteral("1.2.3")},
        {QStringLiteral("minimumUpdatableVersion"), QStringLiteral("1.0.0")},
        {QStringLiteral("sourceRevision"), QString(40, QLatin1Char('a'))},
        {QStringLiteral("rollout"), QJsonObject{
             {QStringLiteral("percentage"), percentage},
             {QStringLiteral("seed"), QString(64, QLatin1Char('b'))}}},
        {QStringLiteral("installer"), QJsonObject{
             {QStringLiteral("url"), QStringLiteral("https://updates.example.test/stable/ChatRoom-1.2.3-Setup.exe")},
             {QStringLiteral("size"), 123456},
             {QStringLiteral("sha256"), QString(64, QLatin1Char('c'))},
             {QStringLiteral("authenticodeSha256Thumbprint"), QString(64, QLatin1Char('d'))}}}
    };
}

QByteArray bytes(const QJsonObject &value) {
    return QJsonDocument(value).toJson(QJsonDocument::Compact) + '\n';
}

Policy::Context context(const QString &version = QStringLiteral("1.1.0")) {
    Policy::Context value;
    value.currentVersion = version;
    value.channel = QStringLiteral("stable");
    value.stableDeviceId = QStringLiteral("70000000-0000-4000-8000-000000000001");
    value.nowUtc = QDateTime::fromString(QStringLiteral("2026-08-15T12:00:00Z"), Qt::ISODate);
    return value;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    auto value = manifest();
    auto decision = Policy::evaluate(bytes(value), value, context());
    if (!check(decision.outcome == Policy::Outcome::Eligible,
               QStringLiteral("eligible update was not selected"))
            || !check(decision.acceptedSequence == 42 && decision.acceptedManifestSha256.size() == 32,
                      QStringLiteral("accepted replay state is incomplete"))
            || !check(decision.installerSha256 == QByteArray(32, static_cast<char>(0xcc)),
                      QStringLiteral("installer digest was not decoded"))
            || !check(decision.installerSize == 123456,
                      QStringLiteral("installer size was not retained"))
            || !check(decision.rolloutBucket >= 0 && decision.rolloutBucket < 100,
                      QStringLiteral("rollout bucket is outside its bound"))) return 1;
    if (!check(decision.rolloutBucket == 5,
               QStringLiteral("cross-language rollout bucket changed"))) return 1;

    if (!check(Policy::evaluate(bytes(value), value, context(QStringLiteral("1.2.3"))).outcome
                   == Policy::Outcome::NoUpdate,
               QStringLiteral("same version was offered as an update"))
            || !check(Policy::evaluate(bytes(value), value, context(QStringLiteral("0.9.0"))).outcome
                          == Policy::Outcome::ManualUpdateRequired,
                      QStringLiteral("below-minimum client was not routed to manual update"))) return 1;

    auto deferred = manifest(0);
    if (!check(Policy::evaluate(bytes(deferred), deferred, context()).outcome
                   == Policy::Outcome::DeferredByRollout,
               QStringLiteral("zero-percent rollout was not deferred"))) return 1;

    auto replayContext = context();
    replayContext.highestAcceptedSequence = decision.acceptedSequence;
    replayContext.highestAcceptedManifestSha256 = decision.acceptedManifestSha256;
    if (!check(Policy::evaluate(bytes(value), value, replayContext).outcome == Policy::Outcome::Eligible,
               QStringLiteral("identical accepted manifest was not idempotent"))) return 1;
    replayContext.highestAcceptedManifestSha256.fill('\x01');
    if (!check(Policy::evaluate(bytes(value), value, replayContext).outcome == Policy::Outcome::Rejected,
               QStringLiteral("same-sequence conflicting manifest was accepted"))) return 1;
    replayContext.highestAcceptedSequence = 43;
    if (!check(Policy::evaluate(bytes(value), value, replayContext).outcome == Policy::Outcome::Rejected,
               QStringLiteral("lower-sequence replay was accepted"))) return 1;

    for (const auto &badTime : {
             QDateTime::fromString(QStringLiteral("2026-08-11T23:59:59Z"), Qt::ISODate),
             QDateTime::fromString(QStringLiteral("2026-08-19T00:00:00Z"), Qt::ISODate)}) {
        auto badContext = context();
        badContext.nowUtc = badTime;
        if (!check(Policy::evaluate(bytes(value), value, badContext).outcome == Policy::Outcome::Rejected,
                   QStringLiteral("out-of-window manifest was accepted"))) return 1;
    }

    auto hostile = value;
    auto installer = hostile.value(QStringLiteral("installer")).toObject();
    installer.insert(QStringLiteral("url"), QStringLiteral("https://updates.example.test/a/../ChatRoom-1.2.3-Setup.exe"));
    hostile.insert(QStringLiteral("installer"), installer);
    if (!check(Policy::evaluate(bytes(hostile), hostile, context()).outcome == Policy::Outcome::Rejected,
               QStringLiteral("path-traversing installer URL was accepted"))) return 1;
    hostile = value;
    hostile.insert(QStringLiteral("unexpected"), true);
    if (!check(Policy::evaluate(bytes(hostile), hostile, context()).outcome == Policy::Outcome::Rejected,
               QStringLiteral("unknown manifest field was accepted"))) return 1;
    hostile = value;
    installer = hostile.value(QStringLiteral("installer")).toObject();
    installer.insert(QStringLiteral("size"), 2147483649.0);
    hostile.insert(QStringLiteral("installer"), installer);
    if (!check(Policy::evaluate(bytes(hostile), hostile, context()).outcome == Policy::Outcome::Rejected,
               QStringLiteral("installer above 2 GiB was accepted"))) return 1;
    hostile = value;
    hostile.insert(QStringLiteral("version"), QStringLiteral("1.2.4"));
    if (!check(Policy::evaluate(bytes(value), hostile, context()).outcome == Policy::Outcome::Rejected,
               QStringLiteral("object mismatched with signed bytes was accepted"))) return 1;

    qInfo() << "[UpdateManifestDecisionPolicyTest] PASS";
    return 0;
}
