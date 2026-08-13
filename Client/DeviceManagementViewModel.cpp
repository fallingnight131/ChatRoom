#include "DeviceManagementViewModel.h"

#include <QSet>

DeviceManagementViewModel::DeviceManagementViewModel(
        ListCommand list, RevokeCommand revoke, QObject *parent)
    : QObject(parent), m_list(std::move(list)), m_revoke(std::move(revoke)) {}

QVector<DeviceManagementViewModel::Device> DeviceManagementViewModel::devices() const {
    return m_devices;
}
bool DeviceManagementViewModel::authenticated() const { return m_authenticated; }
bool DeviceManagementViewModel::loading() const { return m_loading; }
QString DeviceManagementViewModel::revokingDeviceId() const { return m_revokingDeviceId; }
QString DeviceManagementViewModel::failure() const { return m_failure; }

void DeviceManagementViewModel::setAuthenticated(
        bool authenticated, const QString &currentDeviceId) {
    if (authenticated && currentDeviceId != m_currentDeviceId) m_devices.clear();
    m_authenticated = authenticated;
    resetRequests();
    if (!authenticated) {
        m_currentDeviceId.clear();
    } else {
        m_currentDeviceId = currentDeviceId;
    }
    emit changed();
}

bool DeviceManagementViewModel::refresh() {
    if (!m_authenticated || m_loading || !m_revokingDeviceId.isEmpty() || !m_list)
        return false;
    m_failure.clear();
    m_loading = true;
    try {
        m_listRequestId = m_list();
    } catch (...) {
        m_listRequestId.clear();
    }
    if (m_listRequestId.isEmpty()) {
        m_loading = false;
        m_failure = QStringLiteral("无法加载登录设备");
        emit changed();
        return false;
    }
    emit changed();
    return true;
}

bool DeviceManagementViewModel::revoke(const QString &deviceId) {
    if (!m_authenticated || m_loading || !m_revokingDeviceId.isEmpty()
            || deviceId.isEmpty() || deviceId == m_currentDeviceId || !m_revoke)
        return false;
    bool target = false;
    for (const Device &device : m_devices)
        target = target || (device.deviceId == deviceId && !device.current);
    if (!target) return false;
    m_failure.clear();
    m_revokingDeviceId = deviceId;
    try {
        m_revokeRequestId = m_revoke(deviceId);
    } catch (...) {
        m_revokeRequestId.clear();
    }
    if (m_revokeRequestId.isEmpty()) {
        m_revokingDeviceId.clear();
        m_failure = QStringLiteral("无法撤销该设备");
        emit changed();
        return false;
    }
    emit changed();
    return true;
}

void DeviceManagementViewModel::applyDirectory(
        const QString &requestId, const QVector<Device> &devices) {
    if (requestId != m_listRequestId) return;
    m_listRequestId.clear();
    m_loading = false;
    if (!validDirectory(devices, m_currentDeviceId)) {
        m_failure = QStringLiteral("登录设备数据无效");
        emit changed();
        return;
    }
    m_devices = devices;
    m_failure.clear();
    emit changed();
}

void DeviceManagementViewModel::applyRevoked(
        const QString &requestId, const QString &deviceId) {
    if (requestId != m_revokeRequestId || deviceId != m_revokingDeviceId) return;
    m_revokeRequestId.clear();
    m_revokingDeviceId.clear();
    for (qsizetype i = m_devices.size(); i > 0; --i)
        if (m_devices.at(i - 1).deviceId == deviceId) m_devices.removeAt(i - 1);
    emit changed();
    refresh();
}

void DeviceManagementViewModel::applyProtocolError(const QString &requestId) {
    if (requestId == m_listRequestId) {
        m_listRequestId.clear();
        m_loading = false;
        m_failure = QStringLiteral("无法加载登录设备");
    } else if (requestId == m_revokeRequestId) {
        m_revokeRequestId.clear();
        m_revokingDeviceId.clear();
        m_failure = QStringLiteral("无法撤销该设备");
    } else return;
    emit changed();
}

void DeviceManagementViewModel::resetRequests() {
    m_listRequestId.clear();
    m_revokeRequestId.clear();
    m_revokingDeviceId.clear();
    m_loading = false;
}

bool DeviceManagementViewModel::validDirectory(
        const QVector<Device> &devices, const QString &currentDeviceId) {
    if (devices.isEmpty() || devices.size() > 100 || currentDeviceId.isEmpty()) return false;
    QSet<QString> identifiers;
    int current = 0;
    for (const Device &device : devices) {
        if (device.deviceId.isEmpty() || identifiers.contains(device.deviceId)
                || device.createdAtEpochMs <= 0
                || device.lastSeenAtEpochMs < device.createdAtEpochMs)
            return false;
        identifiers.insert(device.deviceId);
        if (device.current) {
            ++current;
            if (device.deviceId != currentDeviceId) return false;
        }
    }
    return current == 1;
}
