#pragma once

#include "V2WindowsDeviceManagementTransport.h"
#include "WindowsV2MessagingController.h"
#include <QObject>
#include <QUrl>
#include <memory>

class DeviceManagementApplicationService;
class DeviceManagementViewModel;
class V2WindowsConversationDirectoryViewModel;
class V2WindowsConversationParticipantViewModel;
class V2WindowsMessagingViewModel;
class V2WindowsMessageSearchViewModel;
class V2WindowsAccountBlockViewModel;
class WindowsAccountBlockController;

class WindowsDeviceManagementController final : public QObject {
    Q_OBJECT
public:
    WindowsDeviceManagementController(
        QUrl endpoint,
        QString appVersion,
        QString deviceId,
        QString username,
        QByteArray passwordUtf8,
        QWebSocket *socket = nullptr,
        V2WindowsDeviceManagementTransport::SocketHooks hooks = {},
        WindowsV2MessagingController::RepositoryFactory messagingRepositoryFactory = {},
        QObject *parent = nullptr,
        bool enableMessageForwarding = false,
        QList<QUrl> fallbackEndpoints = {},
        bool enableMessageSearch = false,
        bool enableAccountBlocking = false);
    ~WindowsDeviceManagementController() override;

    DeviceManagementViewModel *viewModel() const;
    V2WindowsConversationDirectoryViewModel *conversationDirectoryViewModel() const;
    V2WindowsConversationParticipantViewModel *conversationParticipantViewModel() const;
    V2WindowsMessagingViewModel *messagingViewModel() const;
    V2WindowsMessageSearchViewModel *messageSearchViewModel() const;
    V2WindowsAccountBlockViewModel *accountBlockViewModel() const;
    bool start();
    void stop();

signals:
    void messagingReady();
    void messagingUnavailable();
    void messagingFailure(const QString &safeReason);
    void remoteMessagePublished(
        const QString &conversationId, const QString &messageId,
        const QString &senderAccountId,
        bool authenticatedAccountMentioned);

private:
    std::unique_ptr<V2WindowsDeviceManagementTransport> m_transport;
    std::unique_ptr<DeviceManagementViewModel> m_viewModel;
    std::unique_ptr<DeviceManagementApplicationService> m_service;
    std::unique_ptr<WindowsV2MessagingController> m_messagingController;
    std::unique_ptr<WindowsAccountBlockController> m_accountBlockController;
};
