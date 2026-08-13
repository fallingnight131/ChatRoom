#include "WindowsV2MessagingController.h"

#include "V2LocalMessageRepository.h"
#include "V2WindowsMessagingApplicationService.h"
#include "V2WindowsMessagingViewModel.h"

#include <stdexcept>
#include <utility>

WindowsV2MessagingController::WindowsV2MessagingController(
        V2WindowsDeviceManagementTransport *transport,
        RepositoryFactory repositoryFactory,
        QObject *parent)
    : QObject(parent), m_transport(transport),
      m_repositoryFactory(repositoryFactory ? std::move(repositoryFactory)
          : [](const QString &accountId) {
              return std::make_unique<V2LocalMessageRepository>(
                  V2LocalMessageRepository::defaultDatabasePath(accountId));
          }) {
    if (!m_transport || !m_repositoryFactory)
        throw std::invalid_argument("invalid Windows V2 messaging controller");
    connect(m_transport, &V2WindowsDeviceManagementTransport::authenticated,
            this, [this](const QString &accountId, const QString &deviceId,
                         const QString &sessionId, const QString &) {
        bindAuthenticatedSession(accountId, deviceId, sessionId);
    });
    connect(m_transport, &V2WindowsDeviceManagementTransport::messagingFrameReceived,
            this, &WindowsV2MessagingController::receiveFrame);
    connect(m_transport, &V2WindowsDeviceManagementTransport::stateChanged,
            this, [this](V2WindowsDeviceManagementTransport::State state) {
        if (state == V2WindowsDeviceManagementTransport::State::ReconnectWait
                || state == V2WindowsDeviceManagementTransport::State::Stopped)
            abandonSession();
    });
}

WindowsV2MessagingController::~WindowsV2MessagingController() {
    abandonSession();
}

V2WindowsMessagingViewModel *WindowsV2MessagingController::viewModel() const {
    return m_viewModel.get();
}

bool WindowsV2MessagingController::openConversation(const QString &conversationId) {
    if (!m_viewModel || !m_service || !m_viewModel->openConversation(conversationId))
        return false;
    if (m_service->connected()) m_service->requestHistory(conversationId);
    return true;
}

void WindowsV2MessagingController::bindAuthenticatedSession(
        const QString &accountId, const QString &deviceId,
        const QString &sessionId) {
    if (!m_repository || accountId != m_accountId || deviceId != m_deviceId) {
        m_viewModel.reset();
        m_service.reset();
        m_repository.reset();
        m_accountId = accountId;
        m_deviceId = deviceId;
        m_repository = m_repositoryFactory(accountId);
        if (!m_repository || !m_repository->initialize()) {
            const QString detail = m_repository ? m_repository->lastError()
                                                 : QStringLiteral("repository unavailable");
            m_repository.reset();
            emit failure(QStringLiteral("无法打开 V2 消息缓存：%1").arg(detail));
            return;
        }
        try {
            m_service = std::make_unique<V2WindowsMessagingApplicationService>(
                m_repository.get(), accountId, deviceId,
                [this](const QByteArray &frame) {
                    return m_transport->sendMessagingFrame(frame);
                });
            m_viewModel = std::make_unique<V2WindowsMessagingViewModel>(
                accountId,
                [this](const QString &conversationId) {
                    return m_service->hydrate(conversationId);
                },
                [this](const QString &conversationId, const QString &targetMessageId,
                       const QString &text, V2LocalMessageRepository::Message *message) {
                    return m_service->stageReply(
                        conversationId, targetMessageId, text, message);
                },
                [this](const QString &conversationId, const QString &clientMessageId) {
                    return m_service->retry(conversationId, clientMessageId);
                });
        } catch (const std::exception &exception) {
            m_viewModel.reset();
            m_service.reset();
            emit failure(QStringLiteral("无法启动 V2 消息：%1")
                             .arg(QString::fromUtf8(exception.what())));
            return;
        }
    }
    if (!m_service->connectSession(sessionId)) {
        emit failure(QStringLiteral("无法绑定 V2 消息会话：%1")
                         .arg(m_service->lastError()));
        m_transport->rejectMessagingProtocol();
        return;
    }
    emit ready();
}

void WindowsV2MessagingController::receiveFrame(const QByteArray &frame) {
    if (!m_service) {
        m_transport->rejectMessagingProtocol();
        return;
    }
    const auto outcome = m_service->receiveFrame(frame);
    if (outcome.type
            == V2WindowsMessagingApplicationService::OutcomeType::ProtocolFailure) {
        emit failure(QStringLiteral("V2 消息协议校验失败"));
        m_transport->rejectMessagingProtocol();
        return;
    }
    if (m_viewModel && outcome.type
            != V2WindowsMessagingApplicationService::OutcomeType::None)
        m_viewModel->refresh();
}

void WindowsV2MessagingController::abandonSession() {
    if (m_service && m_service->connected()) {
        m_service->disconnectSession();
        emit unavailable();
    }
}
