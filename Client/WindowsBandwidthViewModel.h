#pragma once

#include <QObject>

class WindowsBandwidthPreferenceRepository;

class WindowsBandwidthViewModel final : public QObject {
    Q_OBJECT
public:
    explicit WindowsBandwidthViewModel(
        WindowsBandwidthPreferenceRepository *repository,
        QObject *parent = nullptr);

    bool enabled() const { return m_enabled; }
    bool saveFailed() const { return m_saveFailed; }
    bool select(bool enabled);

signals:
    void changed();

private:
    WindowsBandwidthPreferenceRepository *m_repository;
    bool m_enabled = false;
    bool m_saveFailed = false;
};
