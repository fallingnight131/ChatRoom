#include "WindowsLocalePreferenceRepository.h"

#include <QSettings>

namespace {
const auto PreferenceKey = QStringLiteral("ui/locale");
}

WindowsLocalePreferenceRepository::WindowsLocalePreferenceRepository(
        QSettings &settings)
    : m_settings(settings) {
}

WindowsLocale WindowsLocalePreferenceRepository::load() const {
    const auto stored = m_settings.value(PreferenceKey).toString();
    return WindowsLocaleCatalog::parse(stored).value_or(
        WindowsLocaleCatalog::defaultLocale());
}

bool WindowsLocalePreferenceRepository::save(WindowsLocale locale) {
    m_settings.setValue(PreferenceKey, WindowsLocaleCatalog::code(locale));
    m_settings.sync();
    return m_settings.status() == QSettings::NoError;
}
