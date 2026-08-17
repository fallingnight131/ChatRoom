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
    const bool hadPrevious = m_settings.contains(PreferenceKey);
    const QVariant previous = m_settings.value(PreferenceKey);
    m_settings.setValue(PreferenceKey, WindowsLocaleCatalog::code(locale));
    m_settings.sync();
    if (m_settings.status() == QSettings::NoError) return true;
    if (hadPrevious)
        m_settings.setValue(PreferenceKey, previous);
    else
        m_settings.remove(PreferenceKey);
    m_settings.sync();
    return false;
}
