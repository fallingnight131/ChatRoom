#include "WindowsBandwidthPolicy.h"

#include <stdexcept>
#include <utility>

bool WindowsBandwidthPolicy::shouldAutoRequestAvatar(
        const QString &accountId, bool lowBandwidthEnabled, bool cached) {
    return !accountId.isEmpty() && !lowBandwidthEnabled && !cached;
}

WindowsAvatarRequestCoordinator::WindowsAvatarRequestCoordinator(Dispatch dispatch)
    : m_dispatch(std::move(dispatch)) {
    if (!m_dispatch) throw std::invalid_argument("invalid avatar request dispatch");
}

void WindowsAvatarRequestCoordinator::setLowBandwidthEnabled(bool enabled) {
    m_lowBandwidthEnabled = enabled;
}

bool WindowsAvatarRequestCoordinator::request(
        const QString &accountId, bool cached, bool explicitRequest) {
    if (accountId.isEmpty()) return false;
    if (!explicitRequest && !WindowsBandwidthPolicy::shouldAutoRequestAvatar(
            accountId, m_lowBandwidthEnabled, cached))
        return false;
    m_dispatch(accountId);
    return true;
}
