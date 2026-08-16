#include "WindowsAccountBlockController.h"

#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsAccountBlockDirectoryViewModel.h"
#include "V2WindowsDeviceManagementTransport.h"

#include <stdexcept>
#include <utility>

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
    m_directoryViewModel = std::make_unique<V2WindowsAccountBlockDirectoryViewModel>(
        [this](const QString &after) { return list(after); });
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
        const bool actorChanged = m_actorAccountId != accountId;
        m_protocol.bindSession(standard(sessionId), standard(accountId));
        m_viewModel->bindSession(accountId);
        m_directoryViewModel->bindSession(actorChanged);
        m_actorAccountId = accountId;
    } catch (...) {
        m_transport->rejectMessagingProtocol();
    }
}

bool WindowsAccountBlockController::list(const QString &afterTargetAccountId) {
    try {
        const auto command = m_protocol.listAccountBlocks(
            standard(afterTargetAccountId), 100);
        const QByteArray frame(command.bytes.data(), static_cast<qsizetype>(command.bytes.size()));
        if (m_transport->sendAccountBlockFrame(frame)) return true;
        m_protocol.abandon(command.requestId);
    } catch (...) {
    }
    return false;
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
        } else if (event.type == V2WindowsAccountBlockProtocolClient::EventType::DirectoryPage) {
            QVector<V2WindowsAccountBlockDirectoryViewModel::Row> rows;
            rows.reserve(static_cast<qsizetype>(event.blocks.size()));
            for (const auto &block : event.blocks) {
                rows.append({qt(block.targetAccountId), qt(block.targetDisplayName),
                             block.blockedAtEpochMs});
            }
            m_directoryViewModel->applyPage(
                std::move(rows), qt(event.nextAfterTargetAccountId), event.hasMore);
        } else if (event.clientOperationId.empty()) {
            m_directoryViewModel->applyFailure(
                event.retryable ? QStringLiteral("屏蔽目录暂不可用，可重试")
                                : QStringLiteral("无法读取屏蔽目录"));
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
    m_directoryViewModel->setUnavailable();
}
