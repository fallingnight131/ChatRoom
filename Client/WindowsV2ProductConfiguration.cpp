#include "WindowsV2ProductConfiguration.h"

namespace {
WindowsV2ProductConfiguration::Value disabled(const QString &error = {}) {
    WindowsV2ProductConfiguration::Value value;
    value.error = error;
    return value;
}
}

WindowsV2ProductConfiguration::Value
WindowsV2ProductConfiguration::validate(const QString &endpoint) {
    const QUrl parsed(endpoint, QUrl::StrictMode);
    if (!parsed.isValid() || parsed.scheme() != QStringLiteral("wss")
            || parsed.host().isEmpty() || !parsed.userInfo().isEmpty()
            || parsed.path(QUrl::FullyEncoded) != QStringLiteral("/v2/windows")
            || parsed.hasQuery() || parsed.hasFragment() || parsed.port(-1) == 0
            || parsed.toEncoded() != endpoint.toUtf8()) {
        return disabled(QStringLiteral("Windows V2 WSS endpoint is invalid"));
    }
    Value value;
    value.enabled = true;
    value.endpoint = parsed;
    return value;
}

WindowsV2ProductConfiguration::Value WindowsV2ProductConfiguration::fromBuild() {
#ifndef CHAT_WINDOWS_V2_CONFIGURATION_ENABLED
    return disabled();
#elif !defined(CHAT_WINDOWS_V2_WSS_URL)
    return disabled(QStringLiteral("Windows V2 build configuration is incomplete"));
#else
    return validate(QStringLiteral(CHAT_WINDOWS_V2_WSS_URL));
#endif
}
