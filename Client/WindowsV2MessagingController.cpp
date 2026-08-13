#include "WindowsV2MessagingController.h"

#include "V2LocalMessageRepository.h"
#include "V2WindowsMessagingApplicationService.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsConversationDirectoryProtocolClient.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantProtocolClient.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

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
    m_directoryProtocol =
        std::make_unique<V2WindowsConversationDirectoryProtocolClient>();
    m_directoryViewModel =
        std::make_unique<V2WindowsConversationDirectoryViewModel>(
            [this] { return requestDirectory(false); },
            [this] { return requestDirectory(true); },
            [this](const QString &conversationId) {
                return openConversation(conversationId);
            });
    m_participantProtocol =
        std::make_unique<V2WindowsConversationParticipantProtocolClient>();
    m_participantViewModel =
        std::make_unique<V2WindowsConversationParticipantViewModel>(
            [this](const QString &conversationId, bool continuation) {
                return requestParticipants(conversationId, continuation);
            });
}

WindowsV2MessagingController::~WindowsV2MessagingController() {
    abandonSession();
}

V2WindowsMessagingViewModel *WindowsV2MessagingController::viewModel() const {
    return m_viewModel.get();
}

V2WindowsConversationDirectoryViewModel *
WindowsV2MessagingController::directoryViewModel() const {
    return m_directoryViewModel.get();
}

V2WindowsConversationParticipantViewModel *
WindowsV2MessagingController::participantViewModel() const {
    return m_participantViewModel.get();
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
            m_transport->rejectMessagingProtocol();
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
                       const QString &text, V2LocalMessageRepository::Message *message,
                       const QList<V2LocalMessageRepository::Mention> &mentions) {
                    return m_service->stageReply(
                        conversationId, targetMessageId, text, message, mentions);
                },
                [this](const QString &conversationId, const QString &clientMessageId) {
                    return m_service->retry(conversationId, clientMessageId);
                },
                [this](const QString &conversationId, const QString &messageId,
                       V2LocalMessageRepository::ReactionKind reaction) {
                    return m_service->setReaction(conversationId, messageId, reaction);
                },
                [this](const QString &conversationId, const QString &clientOperationId) {
                    return m_service->retryReaction(conversationId, clientOperationId);
                },
                [this](const QString &conversationId, const QString &messageId) {
                    return m_service->setPin(conversationId, messageId);
                },
                [this](const QString &conversationId, const QString &clientOperationId) {
                    return m_service->retryPin(conversationId, clientOperationId);
                },
                [this](const QString &conversationId, const QString &messageId,
                       const QString &text,
                       const QList<V2LocalMessageRepository::Mention> &mentions) {
                    return m_service->editMessage(
                        conversationId, messageId, text, mentions);
                },
                [this](const QString &conversationId, const QString &operationId) {
                    return m_service->retryEdit(conversationId, operationId);
                },
                [this](const QString &conversationId, const QString &operationId) {
                    return m_service->rebaseEdit(conversationId, operationId);
                },
                [this](const QString &operationId) {
                    return m_service->discardEdit(operationId);
                });
        } catch (const std::exception &exception) {
            m_viewModel.reset();
            m_service.reset();
            emit failure(QStringLiteral("无法启动 V2 消息：%1")
                             .arg(QString::fromUtf8(exception.what())));
            m_transport->rejectMessagingProtocol();
            return;
        }
    }
    if (!m_service->connectSession(sessionId)) {
        emit failure(QStringLiteral("无法绑定 V2 消息会话：%1")
                         .arg(m_service->lastError()));
        m_transport->rejectMessagingProtocol();
        return;
    }
    try {
        m_directoryProtocol->bindSession(sessionId.toStdString());
        m_participantProtocol->bindSession(sessionId.toStdString());
    } catch (...) {
        m_transport->rejectMessagingProtocol();
        return;
    }
    emit ready();
    m_directoryViewModel->refresh();
}

void WindowsV2MessagingController::receiveFrame(const QByteArray &frame) {
    if (!m_service) {
        m_transport->rejectMessagingProtocol();
        return;
    }
    chat::v2::Envelope envelope;
    if (!envelope.ParseFromArray(frame.constData(), static_cast<int>(frame.size()))) {
        m_transport->rejectMessagingProtocol();
        return;
    }
    const QString requestId = QString::fromStdString(envelope.request_id());
    if (envelope.message_type()
            == chat::v2::MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE
            || (envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR
                && m_participantRequests.contains(requestId))) {
        try {
            const auto event = m_participantProtocol->receive(
                std::string(frame.constData(), static_cast<std::size_t>(frame.size())));
            const bool append = m_participantRequests.take(requestId);
            const QString conversationId = QString::fromStdString(event.conversationId);
            if (event.type
                    == V2WindowsConversationParticipantProtocolClient::EventType::ProtocolError) {
                m_participantViewModel->applyFailure(
                    conversationId, QStringLiteral("无法读取成员列表"));
                return;
            }
            QVector<V2WindowsConversationParticipantViewModel::Row> rows;
            rows.reserve(static_cast<qsizetype>(event.participants.size()));
            for (const auto &participant : event.participants) {
                rows.append({QString::fromStdString(participant.accountId),
                    QString::fromStdString(participant.displayName),
                    participant.role
                            == V2WindowsConversationParticipantProtocolClient::Role::Owner
                        ? QStringLiteral("群主")
                        : participant.role
                                == V2WindowsConversationParticipantProtocolClient::Role::Admin
                            ? QStringLiteral("管理员") : QStringLiteral("成员")});
            }
            m_participantViewModel->applyPage(
                conversationId, std::move(rows), append, event.hasMore);
            if (conversationId == m_participantViewModel->conversationId())
                m_participantCursor = event.nextAccountId;
            return;
        } catch (...) {
            m_transport->rejectMessagingProtocol();
            return;
        }
    }
    if (envelope.message_type()
            == chat::v2::MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE
            || (envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR
                && m_directoryRequestIds.contains(requestId))) {
        try {
            const auto event = m_directoryProtocol->receive(
                std::string(frame.constData(), static_cast<std::size_t>(frame.size())));
            if (event.type == V2WindowsConversationDirectoryProtocolClient::EventType::ProtocolError) {
                m_directoryRequestIds.remove(requestId);
                m_directoryViewModel->applyFailure(
                    event.retryable ? QStringLiteral("会话列表暂时不可用")
                                    : QStringLiteral("无法读取会话列表"));
                return;
            }
            QVector<V2WindowsConversationDirectoryViewModel::Row> rows;
            rows.reserve(static_cast<qsizetype>(event.conversations.size()));
            for (const auto &item : event.conversations) {
                const auto unread = item.latestSequence - item.lastReadSequence;
                const bool direct = item.kind
                    == V2WindowsConversationDirectoryProtocolClient::Kind::Direct;
                rows.append({QString::fromStdString(item.conversationId),
                    QString::fromStdString(item.displayName),
                    direct ? QStringLiteral("私聊") : QStringLiteral("群聊"),
                    direct ? QString()
                           : item.role == V2WindowsConversationDirectoryProtocolClient::Role::Owner
                               ? QStringLiteral("群主")
                               : item.role == V2WindowsConversationDirectoryProtocolClient::Role::Admin
                                   ? QStringLiteral("管理员") : QStringLiteral("成员"),
                    static_cast<qint64>(unread)});
            }
            m_directoryViewModel->applyPage(
                std::move(rows), m_directoryContinuationPending, event.hasMore);
            m_directoryContinuationPending = false;
            m_directoryRequestIds.remove(requestId);
            m_directoryCursor = event.next;
            return;
        } catch (...) {
            m_transport->rejectMessagingProtocol();
            return;
        }
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
    m_directoryProtocol->clearSession();
    m_participantProtocol->clearSession();
    m_directoryRequestIds.clear();
    m_directoryContinuationPending = false;
    m_directoryCursor = {};
    m_directoryViewModel->setUnavailable();
    m_participantCursor.clear();
    m_participantRequests.clear();
    m_participantViewModel->setUnavailable();
}

bool WindowsV2MessagingController::requestParticipants(
        const QString &conversationId, bool continuation) {
    try {
        if (!continuation) m_participantCursor.clear();
        const auto command = m_participantProtocol->list(
            conversationId.toStdString(), 100,
            continuation ? m_participantCursor : std::string{});
        if (m_transport->sendMessagingFrame(QByteArray(
                command.bytes.data(), static_cast<qsizetype>(command.bytes.size())))) {
            m_participantRequests.insert(
                QString::fromStdString(command.requestId), continuation);
            return true;
        }
    } catch (...) {}
    return false;
}

bool WindowsV2MessagingController::requestDirectory(bool continuation) {
    try {
        if (!continuation) m_directoryCursor = {};
        const auto command = continuation
            ? m_directoryProtocol->list(100, m_directoryCursor)
            : m_directoryProtocol->list(100);
        m_directoryContinuationPending = continuation;
        if (m_transport->sendMessagingFrame(QByteArray(
                command.bytes.data(), static_cast<qsizetype>(command.bytes.size())))) {
            m_directoryRequestIds.insert(QString::fromStdString(command.requestId));
            return true;
        }
        m_directoryProtocol->clearSession();
    } catch (...) {}
    m_directoryContinuationPending = false;
    return false;
}
