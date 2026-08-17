#pragma once

#include <QString>
#include <functional>

class WindowsBandwidthPolicy final {
public:
    static bool shouldAutoRequestAvatar(
        const QString &accountId, bool lowBandwidthEnabled, bool cached);
};

class WindowsAvatarRequestCoordinator final {
public:
    using Dispatch = std::function<void(const QString &)>;

    explicit WindowsAvatarRequestCoordinator(Dispatch dispatch);
    void setLowBandwidthEnabled(bool enabled);
    bool lowBandwidthEnabled() const { return m_lowBandwidthEnabled; }
    bool request(const QString &accountId, bool cached, bool explicitRequest);

private:
    Dispatch m_dispatch;
    bool m_lowBandwidthEnabled = false;
};
