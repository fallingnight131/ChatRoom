#include "WindowsV2ProductConfiguration.h"

namespace {
WindowsV2ProductConfiguration::Value disabled(const QString &error = {}) {
    WindowsV2ProductConfiguration::Value value;
    value.error = error;
    return value;
}
}

WindowsV2ProductConfiguration::Value
WindowsV2ProductConfiguration::validate(
        const QString &endpoint, const QString &fallbackEndpoint) {
    const auto parse = [](const QString &value) -> QUrl {
        const QUrl parsed(value, QUrl::StrictMode);
        if (!parsed.isValid() || parsed.scheme() != QStringLiteral("wss")
                || parsed.host().isEmpty() || !parsed.userInfo().isEmpty()
                || parsed.path(QUrl::FullyEncoded) != QStringLiteral("/v2/windows")
                || parsed.hasQuery() || parsed.hasFragment() || parsed.port(-1) == 0
                || parsed.toEncoded() != value.toUtf8()) return {};
        return parsed;
    };
    const QUrl parsed = parse(endpoint);
    const QUrl fallback = fallbackEndpoint.isEmpty() ? QUrl{} : parse(fallbackEndpoint);
    if (parsed.isEmpty() || (!fallbackEndpoint.isEmpty()
            && (fallback.isEmpty() || fallback == parsed))) {
        return disabled(QStringLiteral("Windows V2 WSS endpoint is invalid"));
    }
    Value value;
    value.enabled = true;
    value.endpoint = parsed;
    if (!fallback.isEmpty()) value.fallbackEndpoints.push_back(fallback);
    return value;
}

WindowsV2ProductConfiguration::Value WindowsV2ProductConfiguration::fromBuild() {
#ifndef CHAT_WINDOWS_V2_CONFIGURATION_ENABLED
    return disabled();
#elif !defined(CHAT_WINDOWS_V2_WSS_URL)
    return disabled(QStringLiteral("Windows V2 build configuration is incomplete"));
#else
    Value value = validate(
        QStringLiteral(CHAT_WINDOWS_V2_WSS_URL),
#ifdef CHAT_WINDOWS_V2_FALLBACK_WSS_URL
        QStringLiteral(CHAT_WINDOWS_V2_FALLBACK_WSS_URL)
#else
        QString{}
#endif
    );
#ifdef CHAT_WINDOWS_V2_FORWARDING_ENABLED
    value.messageForwardingEnabled = value.enabled;
#endif
#ifdef CHAT_WINDOWS_V2_SEARCH_ENABLED
    value.messageSearchEnabled = value.enabled;
#endif
#ifdef CHAT_WINDOWS_V2_NOTIFICATIONS_ENABLED
    value.notificationsEnabled = value.enabled;
#endif
#ifdef CHAT_WINDOWS_V2_ACCOUNT_BLOCKING_ENABLED
    value.accountBlockingEnabled = value.enabled;
#endif
    return value;
#endif
}
