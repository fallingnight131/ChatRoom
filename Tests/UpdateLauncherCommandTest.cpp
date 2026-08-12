#include "UpdateLauncherCommand.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QTemporaryDir>
#include <QUuid>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << "[UpdateLauncherCommandTest]" << message;
    return condition;
}
}

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTemporaryDir root;
    const QString installer = root.filePath(QStringLiteral("ChatRoom-1.2.3-Setup.exe"));
    const QString restart = root.filePath(QStringLiteral("ChatClient.exe"));
    for (const QString &path : {installer, restart}) {
        QFile file(path);
        if (!file.open(QIODevice::WriteOnly) || file.write("fixture") != 7) return 1;
    }
    const QString id = QUuid::createUuid().toString(QUuid::WithoutBraces).toLower();
    QStringList arguments{
        QStringLiteral("--parent-pid"), QStringLiteral("4294967295"),
        QStringLiteral("--installer"), installer,
        QStringLiteral("--installer-size"), QStringLiteral("7"),
        QStringLiteral("--installer-sha256"), QString(64, QLatin1Char('a')),
        QStringLiteral("--signer-thumbprint-sha256"), QString(64, QLatin1Char('b')),
        QStringLiteral("--restart-executable"), restart,
        QStringLiteral("--result-file"), root.filePath(
            QStringLiteral("result-%1.json").arg(id)),
        QStringLiteral("--request-id"), id,
        QStringLiteral("--ready-event"), QStringLiteral(
            "Local\\ChatRoom.UpdateLauncher.Ready.%1").arg(id),
        QStringLiteral("--commit-event"), QStringLiteral(
            "Local\\ChatRoom.UpdateLauncher.Commit.%1").arg(id)};
    UpdateLauncherCommand command;
    QString error;
    if (!check(UpdateLauncherCommand::parse(arguments, &command, &error), error)
            || !check(command.installerSize == 7
                          && command.installerSha256 == QByteArray(32, '\xaa')
                          && command.signerThumbprintSha256 == QByteArray(32, '\xbb'),
                      QStringLiteral("valid launcher metadata changed"))) return 1;

    for (int index : {0, 5, 7, 9, 13, 15, 17, 19}) {
        QStringList invalid = arguments;
        invalid[index] = index == 0 ? QStringLiteral("--unknown")
            : QStringLiteral("INVALID");
        if (!check(!UpdateLauncherCommand::parse(invalid, &command, &error),
                   QStringLiteral("unsafe launcher arguments were accepted at %1").arg(index)))
            return 1;
    }
    QStringList duplicate = arguments;
    duplicate[2] = duplicate[0];
    if (!check(!UpdateLauncherCommand::parse(duplicate, &command, &error),
               QStringLiteral("duplicate launcher option was accepted"))) return 1;

    qInfo() << "[UpdateLauncherCommandTest] PASS";
    return 0;
}
