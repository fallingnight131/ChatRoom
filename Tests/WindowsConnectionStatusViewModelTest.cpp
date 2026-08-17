#include "WindowsConnectionStatusViewModel.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
bool check(bool value, const char *message) {
    if (!value) qCritical().noquote() << message;
    return value;
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    WindowsConnectionStatusViewModel viewModel;
    int changes = 0;
    QObject::connect(&viewModel, &WindowsConnectionStatusViewModel::changed,
                     [&] { ++changes; });

    if (!check(viewModel.state()
                   == WindowsConnectionStatusViewModel::State::Disconnected
                   && viewModel.reconnectAttempt() == 0,
               "connection status did not fail closed to disconnected")) return 1;

    viewModel.setReconnecting(0);
    if (!check(viewModel.state()
                   == WindowsConnectionStatusViewModel::State::Reconnecting
                   && viewModel.reconnectAttempt() == 1 && changes == 1,
               "reconnect state did not normalize its attempt")) return 1;
    viewModel.setReconnecting(1);
    if (!check(changes == 1, "identical reconnect state emitted a duplicate change"))
        return 1;
    viewModel.setReconnecting(2);
    viewModel.setConnected();
    if (!check(viewModel.state()
                   == WindowsConnectionStatusViewModel::State::Connected
                   && viewModel.reconnectAttempt() == 0 && changes == 3,
               "connected state retained reconnect metadata")) return 1;
    viewModel.setDisconnected();
    if (!check(viewModel.state()
                   == WindowsConnectionStatusViewModel::State::Disconnected
                   && viewModel.reconnectAttempt() == 0 && changes == 4,
               "disconnect state did not clear reconnect metadata")) return 1;

    qInfo() << "[WindowsConnectionStatusViewModelTest] PASS";
    return 0;
}
