#pragma once

#include <QString>

class WindowsDeviceIdentityRepository final {
public:
    explicit WindowsDeviceIdentityRepository(QString directoryPath);
    bool loadOrCreate(QString *deviceId, QString *error = nullptr) const;

private:
    bool prepareDirectory(QString *error) const;
    static void fail(QString *error, const QString &message);

    QString m_directoryPath;
};
