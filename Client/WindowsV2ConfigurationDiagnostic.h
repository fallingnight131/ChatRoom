#pragma once

#include "WindowsV2ProductConfiguration.h"

#include <QByteArray>

class WindowsV2ConfigurationDiagnostic final {
public:
    static QByteArray canonicalJson(
        const WindowsV2ProductConfiguration::Value &configuration);
};
