#pragma once

#include <QString>

class WindowsBandwidthPolicy final {
public:
    static bool shouldAutoRequestAvatar(
        const QString &accountId, bool lowBandwidthEnabled, bool cached);
};
