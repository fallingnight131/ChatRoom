#pragma once

#include "V2WindowsDeviceManagementTransport.h"
#include "V2WindowsConversationDirectoryProtocolClient.h"
#include "V2WindowsConversationParticipantProtocolClient.h"

#include <QObject>
#include <QHash>
#include <QSet>
#include <QString>
#include <functional>
#include <memory>

class V2LocalMessageRepository;
class V2WindowsConversationDirectoryViewModel;
class V2WindowsConversationParticipantViewModel;
class V2WindowsMessagingApplicationService;
class V2WindowsMessagingViewModel;

class WindowsV2MessagingController final : public QObject {
    Q_OBJECT
public:
    using RepositoryFactory = std::function<
        std::unique_ptr<V2LocalMessageRepository>(const QString &accountId)>;

    explicit WindowsV2MessagingController(
        V2WindowsDeviceManagementTransport *transport,
        RepositoryFactory repositoryFactory = {},
        QObject *parent = nullptr);
    ~WindowsV2MessagingController() override;

    V2WindowsMessagingViewModel *viewModel() const;
    V2WindowsConversationDirectoryViewModel *directoryViewModel() const;
    V2WindowsConversationParticipantViewModel *participantViewModel() const;
    bool openConversation(const QString &conversationId);

signals:
    void ready();
    void unavailable();
    void failure(const QString &safeReason);

private:
    void bindAuthenticatedSession(
        const QString &accountId, const QString &deviceId,
        const QString &sessionId);
    void receiveFrame(const QByteArray &frame);
    void abandonSession();
    bool requestDirectory(bool continuation);
    bool requestParticipants(const QString &conversationId, bool continuation);

    V2WindowsDeviceManagementTransport *m_transport;
    RepositoryFactory m_repositoryFactory;
    QString m_accountId;
    QString m_deviceId;
    std::unique_ptr<V2LocalMessageRepository> m_repository;
    std::unique_ptr<V2WindowsMessagingApplicationService> m_service;
    std::unique_ptr<V2WindowsMessagingViewModel> m_viewModel;
    std::unique_ptr<V2WindowsConversationDirectoryProtocolClient> m_directoryProtocol;
    std::unique_ptr<V2WindowsConversationDirectoryViewModel> m_directoryViewModel;
    std::unique_ptr<V2WindowsConversationParticipantProtocolClient> m_participantProtocol;
    std::unique_ptr<V2WindowsConversationParticipantViewModel> m_participantViewModel;
    V2WindowsConversationDirectoryProtocolClient::Cursor m_directoryCursor;
    QSet<QString> m_directoryRequestIds;
    bool m_directoryContinuationPending = false;
    std::string m_participantCursor;
    QHash<QString, bool> m_participantRequests;
};
