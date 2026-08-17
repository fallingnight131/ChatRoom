#include "UpdateInstallerTrustVerifier.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDebug>
#include <QFile>
#include <QTemporaryDir>

namespace {
using Verifier = UpdateInstallerTrustVerifier;

bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateInstallerTrustVerifierTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir fixtureDir;
    if (!check(fixtureDir.isValid(), QStringLiteral("temporary directory creation failed")))
        return 1;
    const QString installerPath = fixtureDir.filePath(QStringLiteral("unsigned-update.exe"));
    QFile installer(installerPath);
    const QByteArray payload("unsigned update fixture\0bytes", 29);
    if (!check(installer.open(QIODevice::WriteOnly),
               QStringLiteral("temporary installer creation failed"))
            || !check(installer.write(payload) == payload.size(),
                      QStringLiteral("temporary installer write failed"))
            || !check(installer.flush(),
                      QStringLiteral("temporary installer flush failed"))) return 1;
    installer.close();

    const QByteArray digest = QCryptographicHash::hash(payload, QCryptographicHash::Sha256);
    const auto integrity = Verifier::verifyIntegrity(
        installerPath, payload.size(), digest);
    if (!check(integrity.valid, QStringLiteral("matching payload failed integrity"))
            || !check(!Verifier::verifyIntegrity(
                           installerPath, payload.size() + 1, digest).valid,
                      QStringLiteral("wrong payload size was accepted"))
            || !check(!Verifier::verifyIntegrity(
                           installerPath, payload.size(), QByteArray(32, '\x01')).valid,
                      QStringLiteral("wrong payload digest was accepted"))
            || !check(!Verifier::verifyIntegrity(
                           installerPath, payload.size(), QByteArray(31, '\x01')).valid,
                      QStringLiteral("short expected digest was accepted"))) return 1;

    const auto trust = Verifier::verify(
        installerPath, payload.size(), digest, QByteArray(32, '\x02'));
    const auto launch = Verifier::verifyLaunchAndWait(
        installerPath, payload.size(), digest, QByteArray(32, '\x02'),
        1000);
    if (!check(Verifier::verify(installerPath, payload.size() + 1, digest,
                                QByteArray(32, '\x02')).outcome
                   == Verifier::Outcome::IntegrityRejected,
               QStringLiteral("full trust path ignored integrity failure"))
            || !check(Verifier::verify(installerPath, payload.size(), digest,
                                       QByteArray(31, '\x02')).outcome
                          == Verifier::Outcome::AuthenticodeRejected,
                      QStringLiteral("invalid signer identity was misclassified"))) return 1;
#ifdef Q_OS_WIN
    if (!check(trust.outcome == Verifier::Outcome::AuthenticodeRejected,
               QStringLiteral("unsigned Windows payload outcome %1: %2")
                   .arg(static_cast<int>(trust.outcome)).arg(trust.error))) return 1;
    if (!check(launch.outcome == Verifier::LaunchOutcome::TrustRejected,
               QStringLiteral("unsigned Windows launch outcome %1: %2")
                   .arg(static_cast<int>(launch.outcome)).arg(launch.error))) return 1;
#else
    if (!check(trust.outcome == Verifier::Outcome::UnsupportedPlatform,
               QStringLiteral("non-Windows host claimed Authenticode support"))) return 1;
    if (!check(launch.outcome == Verifier::LaunchOutcome::UnsupportedPlatform,
               QStringLiteral("non-Windows host claimed installer launch support"))) return 1;
#endif

    const auto invalidLaunch = Verifier::verifyLaunchAndWait(
        installerPath, payload.size() + 1, digest,
        QByteArray(32, '\x02'), 1000);
    if (!check(invalidLaunch.outcome == Verifier::LaunchOutcome::TrustRejected,
               QStringLiteral("launch path ignored integrity rejection"))) return 1;
    if (!check(Verifier::verifyLaunchAndWait(
                   installerPath, payload.size(), digest,
                   QByteArray(32, '\x02'), 0).outcome
                   == Verifier::LaunchOutcome::StartFailed,
               QStringLiteral("non-positive installer wait timeout was accepted"))) return 1;

    qInfo() << "[UpdateInstallerTrustVerifierTest] PASS";
    return 0;
}
