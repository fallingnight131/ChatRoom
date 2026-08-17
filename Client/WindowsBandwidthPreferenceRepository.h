#pragma once

class QSettings;

class WindowsBandwidthPreferenceRepository final {
public:
    explicit WindowsBandwidthPreferenceRepository(QSettings &settings);

    bool load() const;
    bool save(bool enabled);

private:
    QSettings &m_settings;
};
