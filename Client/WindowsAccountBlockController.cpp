#include "WindowsAccountBlockController.h"

#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsDeviceManagementTransport.h"

#include <stdexcept>

namespace {
std::string standard(const QString &value) {
    const QByteArray bytes = value.toUtf8();
    return std::string(bytes.constData(), static_cast<std::size_t>(bytes.size()));
}
QString qt(const std::string &value) {
    return QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()));
}
}

WindowsAccountBlockController::WindowsAccountBlockController(
        V2WindowsDeviceManagementTransport *transport, QObject *parent)
    : QObject(parent), m_transport(transport) {
    if (!m_transport) throw std::invalid_argument("account block transport is required");
    m_viewModel = std::make_unique<V2WindowsAccountBlockViewModel>(
        [this](const QString &target, bool blocked, const QString &operation) {
            return submit(target, blocked, operation);
        });
    connect(m_transport, &V2WindowsDeviceManagementTransport::authenticated,
            this, [this](const QString &accountId, const QString &,
                         const QString &sessionId, const QString &) {
        bindSession(accountId, sessionId);
    });
    connect(m_transport, &V2WindowsDeviceManagementTransport::accountBlockFrameReceived,
            this, &WindowsAccountBlockController::receive);
    connect(m_transport, &V2WindowsDeviceManagementTransport::stateChanged,
            this, [this](V2WindowsDeviceManagementTransport::State state) {
        if (state == V2WindowsDeviceManagementTransport::State::ReconnectWait
                || state == V2WindowsDeviceManagementTransport::State::Stopped)
            disconnectSession();
    });
}

WindowsAccountBlockController::~WindowsAccountBlockController() = default;

void WindowsAccountBlockController::bindSession(
        const QString &accountId, const QString &sessionId) {
    try {
        m_protocol.bindSession(standard(sessionId), standard(accountId));
        m_viewModel->bindSession(accountId);
    } catch (...) {
        m_transport->rejectMessagingProtocol();
    }
}

bool WindowsAccountBlockController::submit(
        const QString &targetAccountId, bool blocked,
        const QString &clientOperationId) {
    try {
        const auto command = m_protocol.setAccountBlock(
            standard(targetAccountId), blocked, standard(clientOperationId));
        const QByteArray frame(command.bytes.data(), static_cast<qsizetype>(command.bytes.size()));
        if (m_transport->sendAccountBlockFrame(frame)) return true;
        m_protocol.abandon(command.requestId);
    } catch (...) {
    }
    return false;
}

void WindowsAccountBlockController::receive(const QByteArray &frame) {
    try {
        const auto event = m_protocol.receive(
            std::string(frame.constData(), static_cast<std::size_t>(frame.size())));
        if (event.type == V2WindowsAccountBlockProtocolClient::EventType::Applied) {
            m_viewModel->applyResult(qt(event.targetAccountId), event.blocked,
                                     event.changed, qt(event.clientOperationId));
        } else {
            m_viewModel->applyFailure(qt(event.clientOperationId), event.retryable);
        }
    } catch (...) {
        m_transport->rejectMessagingProtocol();
    }
}

void WindowsAccountBlockController::disconnectSession() {
    m_protocol.clearSession();
    m_viewModel->setUnavailable();
}
