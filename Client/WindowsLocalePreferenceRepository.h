#pragma once

#include "WindowsLocaleCatalog.h"

class QSettings;

class WindowsLocalePreferenceRepository final {
public:
    explicit WindowsLocalePreferenceRepository(QSettings &settings);

    WindowsLocale load() const;
    bool save(WindowsLocale locale);

private:
    QSettings &m_settings;
};
