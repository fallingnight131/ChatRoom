#include "WindowsConnectionStatusViewModel.h"

#include <algorithm>

WindowsConnectionStatusViewModel::WindowsConnectionStatusViewModel(QObject *parent)
    : QObject(parent) {}

void WindowsConnectionStatusViewModel::setConnected() {
    update(State::Connected, 0);
}

void WindowsConnectionStatusViewModel::setDisconnected() {
    update(State::Disconnected, 0);
}

void WindowsConnectionStatusViewModel::setReconnecting(int attempt) {
    update(State::Reconnecting, std::max(1, attempt));
}

void WindowsConnectionStatusViewModel::update(
        State state, int reconnectAttempt) {
    if (m_state == state && m_reconnectAttempt == reconnectAttempt) return;
    m_state = state;
    m_reconnectAttempt = reconnectAttempt;
    emit changed();
}
