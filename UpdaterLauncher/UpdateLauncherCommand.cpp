#include "UpdateLauncherCommand.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QHash>
#include <QSet>
#include <QUuid>

namespace {
constexpr qint64 MaxInstallerBytes = 2LL * 1024 * 1024 * 1024;

bool lowercaseHex(const QString &value, int characters, QByteArray *bytes) {
    if (value.size() != characters) return false;
    for (const QChar character : value) {
        if (!character.isDigit()
                && !(character >= QLatin1Char('a')
                     && character <= QLatin1Char('f'))) return false;
    }
    *bytes = QByteArray::fromHex(value.toLatin1());
    return bytes->size() * 2 == characters;
}

bool safeExistingFile(const QString &path, const QString &suffix) {
    const QFileInfo info(path);
    return info.isAbsolute() && info.exists() && info.isFile() && !info.isSymLink()
        && info.suffix().compare(suffix, Qt::CaseInsensitive) == 0;
}

void fail(QString *error, const QString &message) {
    if (error) *error = message;
}
}

bool UpdateLauncherCommand::parse(
        const QStringList &arguments, UpdateLauncherCommand *command,
        QString *error) {
    if (error) error->clear();
    if (!command || arguments.size() != 18) {
        fail(error, QStringLiteral("update launcher arguments are incomplete"));
        return false;
    }
    QHash<QString, QString> values;
    for (int index = 0; index < arguments.size(); index += 2) {
        const QString key = arguments.at(index);
        const QString value = arguments.at(index + 1);
        if (!key.startsWith(QStringLiteral("--")) || value.isEmpty()
                || value.startsWith(QStringLiteral("--"))
                || values.contains(key)) {
            fail(error, QStringLiteral("update launcher arguments are ambiguous"));
            return false;
        }
        values.insert(key, value);
    }
    const QSet<QString> required{
        QStringLiteral("--parent-pid"), QStringLiteral("--installer"),
        QStringLiteral("--installer-size"), QStringLiteral("--installer-sha256"),
        QStringLiteral("--signer-thumbprint-sha256"),
        QStringLiteral("--restart-executable"), QStringLiteral("--result-file"),
        QStringLiteral("--request-id"), QStringLiteral("--ready-event")};
    QSet<QString> actual;
    for (auto it = values.cbegin(); it != values.cend(); ++it) actual.insert(it.key());
    if (actual != required) {
        fail(error, QStringLiteral("update launcher arguments contain an unknown option"));
        return false;
    }

    bool pidOk = false;
    const quint64 pid = values.value(QStringLiteral("--parent-pid")).toULongLong(&pidOk);
    bool sizeOk = false;
    const qint64 size = values.value(QStringLiteral("--installer-size")).toLongLong(&sizeOk);
    const QString requestId = values.value(QStringLiteral("--request-id"));
    const QUuid uuid(requestId);
    const QString canonicalId = uuid.toString(QUuid::WithoutBraces).toLower();
    QByteArray digest;
    QByteArray thumbprint;
    const QString installer = values.value(QStringLiteral("--installer"));
    const QString restart = values.value(QStringLiteral("--restart-executable"));
    const QString result = values.value(QStringLiteral("--result-file"));
    const QString event = values.value(QStringLiteral("--ready-event"));
    const QFileInfo resultInfo(result);
    const QFileInfo resultDirectory(resultInfo.absolutePath());
    const QString expectedResultName = QStringLiteral("result-%1.json").arg(canonicalId);
    const QString expectedEvent = QStringLiteral(
        "Local\\ChatRoom.UpdateLauncher.Ready.%1").arg(canonicalId);

    if (!pidOk || pid == 0 || pid > 0xffffffffULL
            || pid == static_cast<quint64>(QCoreApplication::applicationPid())
            || !sizeOk || size <= 0 || size > MaxInstallerBytes
            || uuid.isNull() || requestId != canonicalId
            || !safeExistingFile(installer, QStringLiteral("exe"))
            || !safeExistingFile(restart, QStringLiteral("exe"))
            || installer == restart || result == installer || result == restart
            || !resultInfo.isAbsolute() || resultInfo.exists()
            || resultInfo.fileName() != expectedResultName
            || !resultDirectory.exists() || !resultDirectory.isDir()
            || resultDirectory.isSymLink() || event != expectedEvent
            || !lowercaseHex(values.value(QStringLiteral("--installer-sha256")),
                             64, &digest)
            || !lowercaseHex(values.value(
                                 QStringLiteral("--signer-thumbprint-sha256")),
                             64, &thumbprint)) {
        fail(error, QStringLiteral("update launcher argument policy rejected the request"));
        return false;
    }

    command->parentProcessId = static_cast<quint32>(pid);
    command->installerPath = installer;
    command->installerSize = size;
    command->installerSha256 = digest;
    command->signerThumbprintSha256 = thumbprint;
    command->restartExecutablePath = restart;
    command->resultFilePath = result;
    command->requestId = canonicalId;
    command->readyEventName = event;
    return true;
}
