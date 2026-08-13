#pragma once

#include <QObject>
#include <QVector>
#include <QString>
#include <functional>

class DeviceManagementViewModel final : public QObject {
    Q_OBJECT
public:
    enum class Platform { Web, Windows };
    struct Device {
        QString deviceId;
        Platform platform = Platform::Web;
        qint64 createdAtEpochMs = 0;
        qint64 lastSeenAtEpochMs = 0;
        bool current = false;
    };
    using ListCommand = std::function<QString()>;
    using RevokeCommand = std::function<QString(const QString &)>;

    explicit DeviceManagementViewModel(ListCommand list, RevokeCommand revoke,
                                       QObject *parent = nullptr);
    QVector<Device> devices() const;
    bool authenticated() const;
    bool loading() const;
    QString revokingDeviceId() const;
    QString failure() const;

    void setAuthenticated(bool authenticated, const QString &currentDeviceId = {});
    bool refresh();
    bool revoke(const QString &deviceId);
    void applyDirectory(const QString &requestId, const QVector<Device> &devices);
    void applyRevoked(const QString &requestId, const QString &deviceId);
    void applyProtocolError(const QString &requestId);

signals:
    void changed();

private:
    void resetRequests();
    static bool validDirectory(const QVector<Device> &devices, const QString &currentDeviceId);

    ListCommand m_list;
    RevokeCommand m_revoke;
    QVector<Device> m_devices;
    QString m_currentDeviceId;
    QString m_listRequestId;
    QString m_revokeRequestId;
    QString m_revokingDeviceId;
    QString m_failure;
    bool m_authenticated = false;
    bool m_loading = false;
};

Q_DECLARE_METATYPE(DeviceManagementViewModel::Device)
