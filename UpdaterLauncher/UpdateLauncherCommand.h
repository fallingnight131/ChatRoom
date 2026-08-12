#pragma once

#include <QByteArray>
#include <QString>
#include <QStringList>

struct UpdateLauncherCommand {
    quint32 parentProcessId = 0;
    QString installerPath;
    qint64 installerSize = 0;
    QByteArray installerSha256;
    QByteArray signerThumbprintSha256;
    QString restartExecutablePath;
    QString resultFilePath;
    QString requestId;
    QString readyEventName;

    static bool parse(const QStringList &arguments,
                      UpdateLauncherCommand *command,
                      QString *error = nullptr);
};
