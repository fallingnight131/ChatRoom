#include "DeviceManagementApplicationService.h"
#include "DeviceManagementViewModel.h"

#include <stdexcept>
#include <utility>

DeviceManagementApplicationService::DeviceManagementApplicationService(
        DeviceManagementViewModel *viewModel,
        QString username,
        QByteArray passwordUtf8,
        StartCommand start,
        StopCommand stop,
        AuthenticateCommand authenticate,
        int credentialLifetimeMs,
        QObject *parent)
    : QObject(parent), m_viewModel(viewModel), m_username(std::move(username)),
      m_passwordUtf8(std::move(passwordUtf8)), m_start(std::move(start)),
      m_stop(std::move(stop)), m_authenticate(std::move(authenticate)) {
    if (!m_viewModel || m_username.isEmpty() || m_passwordUtf8.isEmpty()
            || !m_start || !m_stop || !m_authenticate
            || credentialLifetimeMs <= 0 || credentialLifetimeMs > 60'000) {
        eraseCredential();
        throw std::invalid_argument("invalid device-management application service");
    }
    m_credentialTimer.setSingleShot(true);
    m_credentialTimer.setInterval(credentialLifetimeMs);
    connect(&m_credentialTimer, &QTimer::timeout, this,
            &DeviceManagementApplicationService::expireCredential);
}

DeviceManagementApplicationService::~DeviceManagementApplicationService() {
    stop();
}

bool DeviceManagementApplicationService::start() {
    if (m_started || m_stopped || !credentialAvailable()) return false;
    try {
        m_start();
    } catch (...) {
        eraseCredential();
        m_viewModel->setAuthenticated(false);
        return false;
    }
    m_started = true;
    m_credentialTimer.start();
    return true;
}

bool DeviceManagementApplicationService::readyForAuthentication() {
    if (!m_started || m_stopped || !credentialAvailable()) return false;
    m_credentialTimer.stop();
    QByteArray credential = std::move(m_passwordUtf8);
    m_passwordUtf8.clear();
    try {
        m_authenticate(m_username, std::move(credential));
    } catch (...) {
        credential.fill('\0');
        eraseCredential();
        m_viewModel->setAuthenticated(false);
        m_stop();
        m_stopped = true;
        return false;
    }
    credential.fill('\0');
    return true;
}

void DeviceManagementApplicationService::authenticated(
        const QString &currentDeviceId) {
    if (!m_started || m_stopped || currentDeviceId.isEmpty()) return;
    eraseCredential();
    m_viewModel->setAuthenticated(true, currentDeviceId);
    m_viewModel->refresh();
}

void DeviceManagementApplicationService::unavailable() {
    if (!m_started || m_stopped) return;
    m_viewModel->setAuthenticated(false);
}

void DeviceManagementApplicationService::authenticationRejected() {
    eraseCredential();
    if (m_viewModel) m_viewModel->setAuthenticated(false);
}

void DeviceManagementApplicationService::stop() {
    if (m_stopped) {
        eraseCredential();
        return;
    }
    m_stopped = true;
    m_credentialTimer.stop();
    eraseCredential();
    if (m_viewModel) m_viewModel->setAuthenticated(false);
    if (m_started && m_stop) m_stop();
}

bool DeviceManagementApplicationService::credentialAvailable() const {
    return !m_passwordUtf8.isEmpty();
}

void DeviceManagementApplicationService::eraseCredential() {
    m_credentialTimer.stop();
    m_passwordUtf8.fill('\0');
    m_passwordUtf8.clear();
}

void DeviceManagementApplicationService::expireCredential() {
    eraseCredential();
    if (m_viewModel) m_viewModel->setAuthenticated(false);
    if (m_started && !m_stopped && m_stop) m_stop();
    m_stopped = true;
}
