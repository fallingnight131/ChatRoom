#include "WindowsDeviceManagementController.h"
#include "DeviceManagementApplicationService.h"
#include "DeviceManagementViewModel.h"

#include <utility>

WindowsDeviceManagementController::WindowsDeviceManagementController(
        QUrl endpoint,
        QString appVersion,
        QString deviceId,
        QString username,
        QByteArray passwordUtf8,
        QWebSocket *socket,
        V2WindowsDeviceManagementTransport::SocketHooks hooks,
        WindowsV2MessagingController::RepositoryFactory messagingRepositoryFactory,
        QObject *parent,
        bool enableMessageForwarding,
        QList<QUrl> fallbackEndpoints)
    : QObject(parent) {
    m_transport = std::make_unique<V2WindowsDeviceManagementTransport>(
        std::move(endpoint), std::move(appVersion), std::move(deviceId),
        socket, std::move(hooks), nullptr, enableMessageForwarding,
        std::move(fallbackEndpoints));
    m_viewModel = std::make_unique<DeviceManagementViewModel>(
        [this] { return m_transport->listDevices(); },
        [this](const QString &target) { return m_transport->revokeDevice(target); });
    m_service = std::make_unique<DeviceManagementApplicationService>(
        m_viewModel.get(), std::move(username), std::move(passwordUtf8),
        [this] { m_transport->start(); },
        [this] { m_transport->stop(); },
        [this](const QString &account, QByteArray password) {
            m_transport->authenticate(account, std::move(password));
        });
    m_messagingController = std::make_unique<WindowsV2MessagingController>(
        m_transport.get(), std::move(messagingRepositoryFactory), nullptr,
        enableMessageForwarding);
    connect(m_messagingController.get(), &WindowsV2MessagingController::ready,
            this, &WindowsDeviceManagementController::messagingReady);
    connect(m_messagingController.get(), &WindowsV2MessagingController::unavailable,
            this, &WindowsDeviceManagementController::messagingUnavailable);
    connect(m_messagingController.get(), &WindowsV2MessagingController::failure,
            this, &WindowsDeviceManagementController::messagingFailure);

    connect(m_transport.get(), &V2WindowsDeviceManagementTransport::stateChanged,
            this, [this](V2WindowsDeviceManagementTransport::State state) {
        using State = V2WindowsDeviceManagementTransport::State;
        if (state == State::ReadyForAuthentication) {
            if (!m_service->readyForAuthentication()) m_service->stop();
        }
        else if (state == State::ReconnectWait || state == State::Stopped)
            m_service->unavailable();
    });
    connect(m_transport.get(), &V2WindowsDeviceManagementTransport::authenticated,
            this, [this](const QString &, const QString &deviceId,
                         const QString &, const QString &) {
        m_service->authenticated(deviceId);
    });
    connect(m_transport.get(),
            &V2WindowsDeviceManagementTransport::authenticationRejected,
            this, [this](qint64) { m_service->authenticationRejected(); });
    connect(m_transport.get(), &V2WindowsDeviceManagementTransport::deviceDirectory,
            m_viewModel.get(), &DeviceManagementViewModel::applyDirectory);
    connect(m_transport.get(), &V2WindowsDeviceManagementTransport::deviceRevoked,
            m_viewModel.get(), &DeviceManagementViewModel::applyRevoked);
    connect(m_transport.get(), &V2WindowsDeviceManagementTransport::protocolError,
            m_viewModel.get(), &DeviceManagementViewModel::applyProtocolError);
}

WindowsDeviceManagementController::~WindowsDeviceManagementController() {
    stop();
}

DeviceManagementViewModel *WindowsDeviceManagementController::viewModel() const {
    return m_viewModel.get();
}

V2WindowsConversationDirectoryViewModel *
WindowsDeviceManagementController::conversationDirectoryViewModel() const {
    return m_messagingController->directoryViewModel();
}

V2WindowsConversationParticipantViewModel *
WindowsDeviceManagementController::conversationParticipantViewModel() const {
    return m_messagingController->participantViewModel();
}

V2WindowsMessagingViewModel *
WindowsDeviceManagementController::messagingViewModel() const {
    return m_messagingController->viewModel();
}

bool WindowsDeviceManagementController::start() {
    return m_service->start();
}

void WindowsDeviceManagementController::stop() {
    if (m_service) m_service->stop();
}
