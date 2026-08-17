#pragma once

#include <QObject>

class WindowsConnectionStatusViewModel final : public QObject {
    Q_OBJECT
public:
    enum class State { Disconnected, Connected, Reconnecting };

    explicit WindowsConnectionStatusViewModel(QObject *parent = nullptr);

    State state() const { return m_state; }
    int reconnectAttempt() const { return m_reconnectAttempt; }

    void setConnected();
    void setDisconnected();
    void setReconnecting(int attempt);

signals:
    void changed();

private:
    void update(State state, int reconnectAttempt);

    State m_state = State::Disconnected;
    int m_reconnectAttempt = 0;
};
