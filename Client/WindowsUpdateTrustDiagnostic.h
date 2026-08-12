#pragma once

#include "WindowsUpdateProductConfiguration.h"

#include <QByteArray>

class WindowsUpdateTrustDiagnostic {
public:
    static QByteArray canonicalJson(
        const WindowsUpdateProductConfiguration::Value &configuration);
};
