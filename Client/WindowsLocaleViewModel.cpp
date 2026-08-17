#include "WindowsLocaleViewModel.h"

#include "WindowsLocalePreferenceRepository.h"

#include <stdexcept>

WindowsLocaleViewModel::WindowsLocaleViewModel(
        WindowsLocalePreferenceRepository *repository, QObject *parent)
    : QObject(parent), m_repository(repository),
      m_locale(repository
          ? repository->load() : WindowsLocaleCatalog::defaultLocale()) {
    if (!m_repository)
        throw std::invalid_argument("locale preference repository is required");
}

bool WindowsLocaleViewModel::select(WindowsLocale locale) {
    if (locale == m_locale) {
        if (!m_failure.isEmpty()) {
            m_failure.clear();
            emit changed();
        }
        return true;
    }
    if (!m_repository->save(locale)) {
        m_failure = WindowsLocaleCatalog::messages(m_locale).localeSaveFailed;
        emit changed();
        return false;
    }
    m_locale = locale;
    m_failure.clear();
    emit changed();
    return true;
}
