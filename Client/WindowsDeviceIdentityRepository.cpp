#include "WindowsDeviceIdentityRepository.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLockFile>
#include <QRegularExpression>
#include <QSaveFile>
#include <QUuid>
#include <utility>

namespace {
const QString IdentityFile = QStringLiteral("device-identity.json");
const QString LockFile = QStringLiteral("device-identity.lock");
const QRegularExpression CanonicalUuid(QStringLiteral(
    R"(^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$)"));
}

WindowsDeviceIdentityRepository::WindowsDeviceIdentityRepository(QString directoryPath)
    : m_directoryPath(QDir::cleanPath(std::move(directoryPath))) {}

void WindowsDeviceIdentityRepository::fail(
        QString *error, const QString &message) {
    if (error) *error = message;
}

bool WindowsDeviceIdentityRepository::prepareDirectory(QString *error) const {
    const QFileInfo requested(m_directoryPath);
    if (m_directoryPath.isEmpty() || !requested.isAbsolute() || requested.isSymLink()) {
        fail(error, QStringLiteral("device identity directory is unsafe"));
        return false;
    }
    if (!QDir().mkpath(m_directoryPath)) {
        fail(error, QStringLiteral("device identity directory cannot be created"));
        return false;
    }
    const QFileInfo actual(m_directoryPath);
    if (!actual.isDir() || actual.isSymLink()
            || !QFile::setPermissions(m_directoryPath,
                    QFileDevice::ReadOwner | QFileDevice::WriteOwner
                        | QFileDevice::ExeOwner)) {
        fail(error, QStringLiteral("device identity directory cannot be secured"));
        return false;
    }
    return true;
}

bool WindowsDeviceIdentityRepository::loadOrCreate(
        QString *deviceId, QString *error) const {
    if (error) error->clear();
    if (!deviceId) {
        fail(error, QStringLiteral("device identity output is required"));
        return false;
    }
    deviceId->clear();
    if (!prepareDirectory(error)) return false;
    QLockFile lock(QDir(m_directoryPath).filePath(LockFile));
    lock.setStaleLockTime(30'000);
    if (!lock.tryLock(1'000)) {
        fail(error, QStringLiteral("device identity is busy"));
        return false;
    }

    const QString path = QDir(m_directoryPath).filePath(IdentityFile);
    const QFileInfo info(path);
    if (info.isSymLink()) {
        fail(error, QStringLiteral("device identity file is unsafe"));
        return false;
    }
    if (info.exists()) {
        if (!info.isFile() || info.size() <= 0 || info.size() > 1'024) {
            fail(error, QStringLiteral("device identity file is unsafe"));
            return false;
        }
        QFile file(path);
        if (!file.open(QIODevice::ReadOnly)) {
            fail(error, QStringLiteral("device identity cannot be read"));
            return false;
        }
        QJsonParseError parseError;
        const auto document = QJsonDocument::fromJson(file.readAll(), &parseError);
        if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
            fail(error, QStringLiteral("device identity JSON is invalid"));
            return false;
        }
        const QJsonObject root = document.object();
        const QStringList keys = root.keys();
        const QString value = root.value(QStringLiteral("deviceId")).toString();
        if (keys != QStringList{QStringLiteral("deviceId"),
                               QStringLiteral("schemaVersion")}
                || root.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
                || !CanonicalUuid.match(value).hasMatch()) {
            fail(error, QStringLiteral("device identity schema is invalid"));
            return false;
        }
        *deviceId = value;
        return true;
    }

    const QString created = QUuid::createUuid()
        .toString(QUuid::WithoutBraces).toLower();
    QSaveFile file(path);
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly)
            || !file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner)) {
        file.cancelWriting();
        fail(error, QStringLiteral("device identity cannot be created securely"));
        return false;
    }
    const QByteArray bytes = QJsonDocument(QJsonObject{
        {QStringLiteral("deviceId"), created},
        {QStringLiteral("schemaVersion"), 1}
    }).toJson(QJsonDocument::Compact) + '\n';
    if (file.write(bytes) != bytes.size() || !file.commit()) {
        fail(error, QStringLiteral("device identity cannot be committed atomically"));
        return false;
    }
    *deviceId = created;
    return true;
}
