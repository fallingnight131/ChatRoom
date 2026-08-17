#include "WindowsBandwidthViewModel.h"

#include "WindowsBandwidthPreferenceRepository.h"

#include <stdexcept>

WindowsBandwidthViewModel::WindowsBandwidthViewModel(
        WindowsBandwidthPreferenceRepository *repository, QObject *parent)
    : QObject(parent), m_repository(repository) {
    if (!m_repository)
        throw std::invalid_argument("invalid Windows bandwidth repository");
    m_enabled = m_repository->load();
}

bool WindowsBandwidthViewModel::select(bool enabled) {
    m_saveFailed = false;
    if (enabled == m_enabled) {
        emit changed();
        return true;
    }
    if (!m_repository->save(enabled)) {
        m_saveFailed = true;
        emit changed();
        return false;
    }
    m_enabled = enabled;
    emit changed();
    return true;
}
