#include "UpdateLifecycleRepository.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLockFile>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QUuid>

#include <utility>

namespace {
const QString PendingName = QStringLiteral("pending-update.json");
const QString LockName = QStringLiteral("update-lifecycle.lock");
const QRegularExpression Version(QStringLiteral(
    R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$)"));

void fail(QString *error, const QString &message) {
    if (error) *error = message;
}

bool safeDirectory(const QString &path, bool create, QString *error) {
    const QFileInfo requested(path);
    if (path.isEmpty() || !requested.isAbsolute() || requested.isSymLink()
            || (create && !QDir().mkpath(path))) {
        fail(error, QStringLiteral("update lifecycle directory is unsafe"));
        return false;
    }
    const QFileInfo actual(path);
    if (!actual.exists() || !actual.isDir() || actual.isSymLink()) {
        fail(error, QStringLiteral("update lifecycle directory is unsafe"));
        return false;
    }
    if (!QFile::setPermissions(path, QFileDevice::ReadOwner
                                      | QFileDevice::WriteOwner
                                      | QFileDevice::ExeOwner)) {
        fail(error, QStringLiteral("update lifecycle directory permissions cannot be restricted"));
        return false;
    }
    return true;
}

bool canonicalId(const QString &value) {
    const QUuid uuid(value);
    return !uuid.isNull()
        && uuid.toString(QUuid::WithoutBraces).toLower() == value;
}

bool validPending(const UpdateLifecycleRepository::Pending &pending) {
    return canonicalId(pending.requestId)
        && Version.match(pending.targetVersion).hasMatch()
        && pending.createdAtUtc.isValid()
        && pending.createdAtUtc.timeSpec() == Qt::UTC;
}

bool exactKeys(const QJsonObject &object) {
    QSet<QString> keys;
    for (const QString &key : object.keys()) keys.insert(key);
    return keys == QSet<QString>{QStringLiteral("schemaVersion"),
                                QStringLiteral("requestId"),
                                QStringLiteral("targetVersion"),
                                QStringLiteral("createdAt")};
}
}

UpdateLifecycleRepository::UpdateLifecycleRepository(
        QString stateDirectory, QString resultDirectory,
        QString runRootDirectory)
    : m_stateDirectory(QDir::cleanPath(std::move(stateDirectory))),
      m_resultDirectory(QDir::cleanPath(std::move(resultDirectory))),
      m_runRootDirectory(QDir::cleanPath(std::move(runRootDirectory))) {}

bool UpdateLifecycleRepository::prepare(QString *error) const {
    if (!safeDirectory(m_stateDirectory, true, error)
            || !safeDirectory(m_resultDirectory, true, error)
            || !safeDirectory(m_runRootDirectory, true, error)) return false;
    const QSet<QString> roots{QFileInfo(m_stateDirectory).absoluteFilePath(),
                              QFileInfo(m_resultDirectory).absoluteFilePath(),
                              QFileInfo(m_runRootDirectory).absoluteFilePath()};
    if (roots.size() != 3) {
        fail(error, QStringLiteral("update lifecycle directories must be separate"));
        return false;
    }
    return true;
}

bool UpdateLifecycleRepository::readPending(
        Pending *pending, bool allowMissing, QString *error) const {
    if (!pending) return false;
    *pending = {};
    const QString path = QDir(m_stateDirectory).filePath(PendingName);
    const QFileInfo info(path);
    if (!info.exists()) {
        if (allowMissing && !info.isSymLink()) return true;
        fail(error, QStringLiteral("pending update record is missing or unsafe"));
        return false;
    }
    if (!info.isFile() || info.isSymLink() || info.size() <= 0
            || info.size() > 16 * 1024) {
        fail(error, QStringLiteral("pending update record is unsafe"));
        return false;
    }
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        fail(error, QStringLiteral("pending update record cannot be read"));
        return false;
    }
    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(file.readAll(), &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()
            || !exactKeys(document.object())) {
        fail(error, QStringLiteral("pending update record schema is invalid"));
        return false;
    }
    const QJsonObject object = document.object();
    Pending parsed;
    parsed.requestId = object.value(QStringLiteral("requestId")).toString();
    parsed.targetVersion = object.value(QStringLiteral("targetVersion")).toString();
    const QString created = object.value(QStringLiteral("createdAt")).toString();
    parsed.createdAtUtc = QDateTime::fromString(created, Qt::ISODate).toUTC();
    if (object.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
            || !created.endsWith(QLatin1Char('Z'))
            || parsed.createdAtUtc.toString(Qt::ISODate) != created
            || !validPending(parsed)) {
        fail(error, QStringLiteral("pending update record policy rejected the record"));
        return false;
    }
    *pending = parsed;
    return true;
}

bool UpdateLifecycleRepository::recordPending(
        const Pending &pending, QString *error) const {
    if (error) error->clear();
    if (!validPending(pending) || !prepare(error)) {
        if (error && error->isEmpty())
            *error = QStringLiteral("pending update value is invalid");
        return false;
    }
    QLockFile lock(QDir(m_stateDirectory).filePath(LockName));
    if (!lock.tryLock(1000)) {
        fail(error, QStringLiteral("update lifecycle is busy"));
        return false;
    }
    const QString pendingPath = QDir(m_stateDirectory).filePath(PendingName);
    const QString resultPath = QDir(m_resultDirectory).filePath(
        QStringLiteral("result-%1.json").arg(pending.requestId));
    const QFileInfo runInfo(QDir(m_runRootDirectory).filePath(
        QStringLiteral("run-%1").arg(pending.requestId)));
    if (QFileInfo::exists(pendingPath) || QFileInfo(pendingPath).isSymLink()
            || QFileInfo::exists(resultPath) || QFileInfo(resultPath).isSymLink()
            || !runInfo.exists() || !runInfo.isDir() || runInfo.isSymLink()) {
        fail(error, QStringLiteral("another or stale update lifecycle exists"));
        return false;
    }
    const QJsonObject object{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("requestId"), pending.requestId},
        {QStringLiteral("targetVersion"), pending.targetVersion},
        {QStringLiteral("createdAt"), pending.createdAtUtc.toString(Qt::ISODate)}
    };
    QSaveFile file(pendingPath);
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly)
            || !file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner)) {
        file.cancelWriting();
        fail(error, QStringLiteral("pending update record cannot be opened securely"));
        return false;
    }
    const QByteArray bytes = QJsonDocument(object).toJson(QJsonDocument::Compact) + '\n';
    if (file.write(bytes) != bytes.size() || !file.commit()) {
        fail(error, QStringLiteral("pending update record cannot be committed"));
        return false;
    }
    return true;
}

UpdateLifecycleRepository::Consumption UpdateLifecycleRepository::consume(
        const QDateTime &nowUtc) const {
    Consumption consumption;
    if (!nowUtc.isValid() || nowUtc.timeSpec() != Qt::UTC
            || !prepare(&consumption.error)) {
        consumption.outcome = ConsumeOutcome::Rejected;
        if (consumption.error.isEmpty())
            consumption.error = QStringLiteral("update lifecycle time is invalid");
        return consumption;
    }
    QLockFile lock(QDir(m_stateDirectory).filePath(LockName));
    if (!lock.tryLock(1000)) {
        consumption.outcome = ConsumeOutcome::Rejected;
        consumption.error = QStringLiteral("update lifecycle is busy");
        return consumption;
    }
    if (!readPending(&consumption.pending, true, &consumption.error)) {
        consumption.outcome = ConsumeOutcome::Rejected;
        return consumption;
    }
    if (consumption.pending.requestId.isEmpty()) return consumption;

    const QString resultPath = QDir(m_resultDirectory).filePath(
        QStringLiteral("result-%1.json").arg(consumption.pending.requestId));
    const QFileInfo resultInfo(resultPath);
    if (resultInfo.isSymLink()) {
        consumption.outcome = ConsumeOutcome::Rejected;
        consumption.error = QStringLiteral("update launcher result file is unsafe");
        return consumption;
    }
    if (!resultInfo.exists()) {
        consumption.outcome = ConsumeOutcome::PendingResult;
        return consumption;
    }
    if (!resultInfo.isFile()
            || resultInfo.size() <= 0 || resultInfo.size() > 16 * 1024) {
        consumption.outcome = ConsumeOutcome::Rejected;
        consumption.error = QStringLiteral("update launcher result file is unsafe");
        return consumption;
    }
    QFile resultFile(resultPath);
    if (!resultFile.open(QIODevice::ReadOnly)
            || !UpdateLauncherResult::parse(
                resultFile.readAll(), consumption.pending.requestId,
                consumption.pending.createdAtUtc, nowUtc,
                &consumption.result, &consumption.error)) {
        consumption.outcome = ConsumeOutcome::Rejected;
        return consumption;
    }
    resultFile.close();
    const QString pendingPath = QDir(m_stateDirectory).filePath(PendingName);
    if (!QFile::remove(pendingPath)) {
        consumption.outcome = ConsumeOutcome::Rejected;
        consumption.error = QStringLiteral("consumed update could not be acknowledged");
        return consumption;
    }
    QFile::remove(resultPath);
    QDir(QDir(m_runRootDirectory).filePath(
        QStringLiteral("run-%1").arg(consumption.pending.requestId))).removeRecursively();
    consumption.outcome = ConsumeOutcome::Completed;
    return consumption;
}
