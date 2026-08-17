#include "WindowsBandwidthPreferenceRepository.h"

#include <QSettings>

namespace {
const auto PreferenceKey = QStringLiteral("ui/lowBandwidth");
}

WindowsBandwidthPreferenceRepository::WindowsBandwidthPreferenceRepository(
        QSettings &settings)
    : m_settings(settings) {
}

bool WindowsBandwidthPreferenceRepository::load() const {
    return m_settings.value(PreferenceKey).toString() == QStringLiteral("true");
}

bool WindowsBandwidthPreferenceRepository::save(bool enabled) {
    const bool hadPrevious = m_settings.contains(PreferenceKey);
    const QVariant previous = m_settings.value(PreferenceKey);
    m_settings.setValue(
        PreferenceKey, enabled ? QStringLiteral("true") : QStringLiteral("false"));
    m_settings.sync();
    if (m_settings.status() == QSettings::NoError) return true;
    if (hadPrevious)
        m_settings.setValue(PreferenceKey, previous);
    else
        m_settings.remove(PreferenceKey);
    m_settings.sync();
    return false;
}
