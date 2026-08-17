#include "WindowsBandwidthPolicy.h"

bool WindowsBandwidthPolicy::shouldAutoRequestAvatar(
        const QString &accountId, bool lowBandwidthEnabled, bool cached) {
    return !accountId.isEmpty() && !lowBandwidthEnabled && !cached;
}
