#include "UpdateStateRepository.h"

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

#include <cmath>
#include <utility>

namespace {
const QString StateFileName = QStringLiteral("update-state.json");
const QString LockFileName = QStringLiteral("update-state.lock");
const QSet<QString> Channels = {QStringLiteral("stable"), QStringLiteral("beta")};
const QRegularExpression DeviceId(QStringLiteral(
    R"(^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$)"));
const QRegularExpression Digest(QStringLiteral(R"(^[0-9a-f]{64}$)"));

bool exactKeys(const QJsonObject &object, const QSet<QString> &expected) {
    QSet<QString> actual;
    for (const auto &key : object.keys()) actual.insert(key);
    return actual == expected;
}

bool safeSequence(const QJsonValue &value, qint64 *result) {
    if (!value.isDouble() || !result) return false;
    const double number = value.toDouble();
    if (!std::isfinite(number) || std::floor(number) != number
            || number < 0 || number > 9007199254740991.0) return false;
    *result = static_cast<qint64>(number);
    return true;
}
}

UpdateStateRepository::UpdateStateRepository(QString directoryPath)
    : m_directoryPath(QDir::cleanPath(std::move(directoryPath))) {}

void UpdateStateRepository::fail(QString *error, const QString &message) {
    if (error) *error = message;
}

bool UpdateStateRepository::prepareDirectory(QString *error) const {
    const QFileInfo requested(m_directoryPath);
    if (m_directoryPath.isEmpty() || !requested.isAbsolute()) {
        fail(error, QStringLiteral("update state directory must be absolute"));
        return false;
    }
    if (requested.isSymLink()) {
        fail(error, QStringLiteral("update state directory is unsafe"));
        return false;
    }
    if (!QDir().mkpath(m_directoryPath)) {
        fail(error, QStringLiteral("update state directory cannot be created"));
        return false;
    }
    const QFileInfo actual(m_directoryPath);
    if (!actual.isDir() || actual.isSymLink()) {
        fail(error, QStringLiteral("update state directory is unsafe"));
        return false;
    }
    if (!QFile::setPermissions(m_directoryPath,
                               QFileDevice::ReadOwner | QFileDevice::WriteOwner
                                   | QFileDevice::ExeOwner)) {
        fail(error, QStringLiteral("update state directory permissions cannot be restricted"));
        return false;
    }
    return true;
}

bool UpdateStateRepository::validate(const State &state, QString *error) {
    QSet<QString> actualChannels;
    for (const auto &channel : state.channels.keys()) actualChannels.insert(channel);
    if (!DeviceId.match(state.stableDeviceId).hasMatch()
            || actualChannels != Channels) {
        fail(error, QStringLiteral("update state identity or channels are invalid"));
        return false;
    }
    for (const auto &channel : Channels) {
        const auto value = state.channels.value(channel);
        if (value.sequence < 0 || value.sequence > 9007199254740991LL
                || (value.sequence == 0 && !value.manifestSha256.isEmpty())
                || (value.sequence > 0 && value.manifestSha256.size() != 32)) {
            fail(error, QStringLiteral("update state replay watermark is invalid"));
            return false;
        }
    }
    return true;
}

bool UpdateStateRepository::readState(
        State *state, bool allowMissing, QString *error) const {
    if (!state) {
        fail(error, QStringLiteral("update state output is required"));
        return false;
    }
    *state = {};
    const QString path = QDir(m_directoryPath).filePath(StateFileName);
    const QFileInfo info(path);
    if (info.isSymLink()) {
        fail(error, QStringLiteral("update state file is unsafe"));
        return false;
    }
    if (!info.exists()) {
        if (allowMissing) return true;
        fail(error, QStringLiteral("update state file is missing"));
        return false;
    }
    if (!info.isFile() || info.size() <= 0 || info.size() > 16 * 1024) {
        fail(error, QStringLiteral("update state file is unsafe"));
        return false;
    }
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        fail(error, QStringLiteral("update state file cannot be read"));
        return false;
    }
    QJsonParseError parseError;
    const auto document = QJsonDocument::fromJson(file.readAll(), &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        fail(error, QStringLiteral("update state JSON is invalid"));
        return false;
    }
    const auto root = document.object();
    const auto channels = root.value(QStringLiteral("channels")).toObject();
    if (!exactKeys(root, {QStringLiteral("schemaVersion"), QStringLiteral("stableDeviceId"),
                          QStringLiteral("channels")})
            || root.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
            || !exactKeys(channels, Channels)) {
        fail(error, QStringLiteral("update state schema is invalid"));
        return false;
    }

    State parsed;
    parsed.stableDeviceId = root.value(QStringLiteral("stableDeviceId")).toString();
    for (const auto &channel : Channels) {
        const auto object = channels.value(channel).toObject();
        qint64 sequence = 0;
        const QString digest = object.value(QStringLiteral("manifestSha256")).toString();
        if (!exactKeys(object, {QStringLiteral("sequence"),
                                QStringLiteral("manifestSha256")})
                || !safeSequence(object.value(QStringLiteral("sequence")), &sequence)
                || (sequence == 0 ? !digest.isEmpty() : !Digest.match(digest).hasMatch())) {
            fail(error, QStringLiteral("update state channel entry is invalid"));
            return false;
        }
        parsed.channels.insert(channel, {sequence, QByteArray::fromHex(digest.toLatin1())});
    }
    if (!validate(parsed, error)) return false;
    *state = parsed;
    return true;
}

bool UpdateStateRepository::writeState(const State &state, QString *error) const {
    if (!validate(state, error)) return false;
    QJsonObject channels;
    for (const auto &channel : Channels) {
        const auto value = state.channels.value(channel);
        channels.insert(channel, QJsonObject{
            {QStringLiteral("sequence"), static_cast<double>(value.sequence)},
            {QStringLiteral("manifestSha256"),
             QString::fromLatin1(value.manifestSha256.toHex())}});
    }
    const QJsonObject root{
        {QStringLiteral("schemaVersion"), 1},
        {QStringLiteral("stableDeviceId"), state.stableDeviceId},
        {QStringLiteral("channels"), channels}
    };
    QSaveFile file(QDir(m_directoryPath).filePath(StateFileName));
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly)
            || !file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner)) {
        file.cancelWriting();
        fail(error, QStringLiteral("update state file cannot be opened securely"));
        return false;
    }
    const QByteArray bytes = QJsonDocument(root).toJson(QJsonDocument::Compact) + '\n';
    if (file.write(bytes) != bytes.size() || !file.commit()) {
        fail(error, QStringLiteral("update state file cannot be committed atomically"));
        return false;
    }
    return true;
}

bool UpdateStateRepository::loadOrCreate(State *state, QString *error) const {
    if (error) error->clear();
    if (!prepareDirectory(error)) return false;
    QLockFile lock(QDir(m_directoryPath).filePath(LockFileName));
    lock.setStaleLockTime(30 * 1000);
    if (!lock.tryLock(1000)) {
        fail(error, QStringLiteral("update state is busy"));
        return false;
    }
    State loaded;
    if (!readState(&loaded, true, error)) return false;
    if (loaded.stableDeviceId.isEmpty()) {
        const QFileInfo statePath(QDir(m_directoryPath).filePath(StateFileName));
        if (statePath.exists() || statePath.isSymLink()) {
            fail(error, QStringLiteral("update state file cannot be safely created"));
            return false;
        }
        loaded.stableDeviceId = QUuid::createUuid().toString(QUuid::WithoutBraces);
        for (const auto &channel : Channels) loaded.channels.insert(channel, {});
        if (!writeState(loaded, error)) return false;
    }
    if (state) *state = loaded;
    return true;
}

UpdateStateRepository::Acceptance UpdateStateRepository::accept(
        const QString &channel,
        qint64 sequence,
        const QByteArray &manifestSha256,
        State *state,
        QString *error) const {
    if (error) error->clear();
    if (!Channels.contains(channel) || sequence <= 0
            || sequence > 9007199254740991LL || manifestSha256.size() != 32) {
        fail(error, QStringLiteral("accepted update watermark is invalid"));
        return Acceptance::Rejected;
    }
    if (!prepareDirectory(error)) return Acceptance::Rejected;
    QLockFile lock(QDir(m_directoryPath).filePath(LockFileName));
    lock.setStaleLockTime(30 * 1000);
    if (!lock.tryLock(1000)) {
        fail(error, QStringLiteral("update state is busy"));
        return Acceptance::Rejected;
    }
    State loaded;
    if (!readState(&loaded, false, error)) return Acceptance::Rejected;
    const auto previous = loaded.channels.value(channel);
    if (sequence < previous.sequence
            || (sequence == previous.sequence
                && manifestSha256 != previous.manifestSha256)) {
        fail(error, QStringLiteral("update watermark replay or conflict rejected"));
        return Acceptance::Rejected;
    }
    if (sequence == previous.sequence) {
        if (state) *state = loaded;
        return Acceptance::Idempotent;
    }
    loaded.channels.insert(channel, {sequence, manifestSha256});
    if (!writeState(loaded, error)) return Acceptance::Rejected;
    if (state) *state = loaded;
    return Acceptance::Stored;
}
