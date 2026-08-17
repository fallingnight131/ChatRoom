#pragma once

#include "WindowsLocaleCatalog.h"

#include <QObject>

class WindowsLocalePreferenceRepository;

class WindowsLocaleViewModel final : public QObject {
    Q_OBJECT
public:
    explicit WindowsLocaleViewModel(
        WindowsLocalePreferenceRepository *repository,
        QObject *parent = nullptr);

    WindowsLocale locale() const { return m_locale; }
    QString failure() const { return m_failure; }
    bool select(WindowsLocale locale);

signals:
    void changed();

private:
    WindowsLocalePreferenceRepository *m_repository;
    WindowsLocale m_locale;
    QString m_failure;
};
