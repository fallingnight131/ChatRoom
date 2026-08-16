#include "WindowsV2MessagingController.h"

#include "V2LocalMessageRepository.h"
#include "V2WindowsMessagingApplicationService.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsConversationDirectoryProtocolClient.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantProtocolClient.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsMessageSearchProtocolClient.h"
#include "V2WindowsMessageSearchViewModel.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace {
V2LocalMessageRepository::Message localMessage(
        const V2WindowsMessagingProtocolClient::Message &source) {
    V2LocalMessageRepository::Message result;
    result.conversationId = QString::fromStdString(source.conversationId);
    result.messageId = QString::fromStdString(source.messageId);
    result.conversationSequence = static_cast<qint64>(source.conversationSequence);
    result.senderAccountId = QString::fromStdString(source.senderAccountId);
    result.senderDeviceId = QString::fromStdString(source.senderDeviceId);
    result.clientMessageId = QString::fromStdString(source.clientMessageId);
    result.text = QString::fromStdString(source.text);
    result.acceptedAtEpochMs = source.acceptedAtEpochMs;
    result.createdAtEpochMs = source.acceptedAtEpochMs;
    result.state = V2LocalMessageRepository::DeliveryState::Accepted;
    result.contentRevision = static_cast<int>(source.contentRevision);
    result.editedAtEpochMs = source.editedAtEpochMs;
    result.hasReply = source.hasReply;
    result.reply = {QString::fromStdString(source.reply.targetMessageId),
        static_cast<qint64>(source.reply.targetConversationSequence),
        QString::fromStdString(source.reply.targetSenderAccountId)};
    for (const auto &mention : source.mentions) {
        result.mentions.append({QString::fromStdString(mention.targetAccountId),
            static_cast<int>(mention.startUtf8Byte),
            static_cast<int>(mention.lengthUtf8Bytes)});
    }
    result.forwarded = source.forwarded;
    return result;
}

QList<V2LocalMessageRepository::Message> transientMessages(
        const V2WindowsMessagingProtocolClient::Event &event) {
    QList<V2LocalMessageRepository::Message> result;
    for (const auto &message : event.messages) result.append(localMessage(message));
    for (const auto &messageId : event.recalledMessageIds) {
        const QString identity = QString::fromStdString(messageId);
        const auto position = std::find_if(result.begin(), result.end(),
            [&](const auto &message) { return message.messageId == identity; });
        if (position != result.end()) {
            position->recalled = true;
            position->text.clear();
            position->mentions.clear();
        }
    }
    for (const auto &edit : event.editChanges) {
        const QString identity = QString::fromStdString(edit.messageId);
        const auto position = std::find_if(result.begin(), result.end(),
            [&](const auto &message) { return message.messageId == identity; });
        if (position == result.end() || position->recalled) continue;
        position->text = QString::fromStdString(edit.text);
        position->contentRevision = static_cast<int>(edit.contentRevision);
        position->editedAtEpochMs = edit.occurredAtEpochMs;
        position->mentions.clear();
        for (const auto &mention : edit.mentions) {
            position->mentions.append({QString::fromStdString(mention.targetAccountId),
                static_cast<int>(mention.startUtf8Byte),
                static_cast<int>(mention.lengthUtf8Bytes)});
        }
    }
    for (const auto &pin : event.pinChanges) {
        const QString identity = QString::fromStdString(pin.messageId);
        const auto position = std::find_if(result.begin(), result.end(),
            [&](const auto &message) { return message.messageId == identity; });
        if (position != result.end()) position->pinned = pin.pinned;
    }
    for (const auto &reaction : event.reactionChanges) {
        const QString identity = QString::fromStdString(reaction.messageId);
        const auto position = std::find_if(result.begin(), result.end(),
            [&](const auto &message) { return message.messageId == identity; });
        if (position == result.end()) continue;
        const auto kind = static_cast<V2LocalMessageRepository::ReactionKind>(
            static_cast<int>(reaction.reaction));
        auto aggregate = std::find_if(position->reactions.begin(), position->reactions.end(),
            [&](const auto &item) { return item.reaction == kind; });
        if (aggregate == position->reactions.end()) {
            position->reactions.append({kind, {}});
            aggregate = std::prev(position->reactions.end());
        }
        const QString actor = QString::fromStdString(reaction.actorAccountId);
        if (reaction.active && !aggregate->actorAccountIds.contains(actor))
            aggregate->actorAccountIds.append(actor);
        else if (!reaction.active)
            aggregate->actorAccountIds.removeAll(actor);
    }
    for (const auto &messageId : event.deletedMessageIds) {
        const QString identity = QString::fromStdString(messageId);
        result.erase(std::remove_if(result.begin(), result.end(),
            [&](const auto &message) { return message.messageId == identity; }), result.end());
    }
    return result;
}
}

WindowsV2MessagingController::WindowsV2MessagingController(
        V2WindowsDeviceManagementTransport *transport,
        RepositoryFactory repositoryFactory,
        QObject *parent,
        bool enableMessageForwarding,
        bool enableMessageSearch)
    : QObject(parent), m_transport(transport),
      m_repositoryFactory(repositoryFactory ? std::move(repositoryFactory)
          : [](const QString &accountId) {
              return std::make_unique<V2LocalMessageRepository>(
                  V2LocalMessageRepository::defaultDatabasePath(accountId));
          }),
      m_messageForwardingEnabled(enableMessageForwarding),
      m_messageSearchEnabled(enableMessageSearch) {
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
    if (m_messageSearchEnabled) {
        m_searchProtocol = std::make_unique<V2WindowsMessageSearchProtocolClient>(
            V2WindowsMessageSearchProtocolClient::RequestIdFactory{},
            V2WindowsMessageSearchProtocolClient::Clock{},
            m_messageForwardingEnabled);
        m_searchContextProtocol = std::make_unique<V2WindowsMessagingProtocolClient>(
            V2WindowsMessagingProtocolClient::RequestIdFactory{},
            V2WindowsMessagingProtocolClient::Clock{},
            m_messageForwardingEnabled);
        m_searchViewModel = std::make_unique<V2WindowsMessageSearchViewModel>(
            [this](const QString &conversationId, const QString &query,
                   quint64 beforeSequence, bool continuation) {
                return requestSearch(
                    conversationId, query, beforeSequence, continuation);
            },
            [this](const QString &conversationId, quint64 conversationSequence,
                   const QString &messageId) {
                return requestSearchContext(
                    conversationId, conversationSequence, messageId);
            });
    }
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

V2WindowsMessageSearchViewModel *
WindowsV2MessagingController::searchViewModel() const {
    return m_searchViewModel.get();
}

bool WindowsV2MessagingController::openConversation(const QString &conversationId) {
    if (!m_viewModel || !m_service || !m_viewModel->openConversation(conversationId))
        return false;
    if (m_service->connected()) m_service->requestHistory(conversationId);
    if (m_searchViewModel) m_searchViewModel->activate(conversationId);
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
                }, V2WindowsMessagingApplicationService::Clock{},
                V2WindowsMessagingApplicationService::ClientMessageIdFactory{},
                m_messageForwardingEnabled);
            m_viewModel = std::make_unique<V2WindowsMessagingViewModel>(
                accountId,
                [this](const QString &conversationId) {
                    return m_service->hydrate(conversationId);
                },
                [this](const QString &conversationId, const QString &text,
                       V2LocalMessageRepository::Message *message,
                       const QList<V2LocalMessageRepository::Mention> &mentions) {
                    return m_service->stageText(
                        conversationId, text, message, mentions);
                },
                [this](const QString &conversationId, const QString &targetMessageId,
                       const QString &text, V2LocalMessageRepository::Message *message,
                       const QList<V2LocalMessageRepository::Mention> &mentions) {
                    return m_service->stageReply(
                        conversationId, targetMessageId, text, message, mentions);
                },
                [this](const QString &conversationId, const QString &draft) {
                    return m_service->saveDraft(conversationId, draft);
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
            if (m_messageForwardingEnabled) {
                m_viewModel->configureForwarding(
                    [this](const QString &sourceConversationId,
                           const QString &sourceMessageId,
                           const QString &targetConversationId,
                           V2LocalMessageRepository::Message *optimistic) {
                        return m_service->stageForward(
                            sourceConversationId, sourceMessageId,
                            targetConversationId, optimistic);
                    });
            }
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
        if (m_searchProtocol) m_searchProtocol->bindSession(sessionId.toStdString());
        if (m_searchContextProtocol)
            m_searchContextProtocol->bindSession(sessionId.toStdString());
    } catch (...) {
        m_transport->rejectMessagingProtocol();
        return;
    }
    emit ready();
    m_directoryViewModel->refresh();
    if (!m_participantViewModel->conversationId().isEmpty())
        m_participantViewModel->refresh();
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
    if (m_searchContextProtocol && m_searchContextRequests.contains(requestId)
            && (envelope.message_type() == chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE
                || envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR)) {
        const SearchContextRequest context = m_searchContextRequests.take(requestId);
        try {
            const auto event = m_searchContextProtocol->receive(
                std::string(frame.constData(), static_cast<std::size_t>(frame.size())));
            if (event.type == V2WindowsMessagingProtocolClient::EventType::ProtocolError) {
                m_searchViewModel->applyContextFailure(
                    context.messageId, QStringLiteral("无法读取消息上下文"));
                return;
            }
            auto messages = transientMessages(event);
            const bool containsTarget = std::any_of(messages.cbegin(), messages.cend(),
                [&](const auto &message) {
                    return message.messageId == context.messageId;
                });
            if (!containsTarget) {
                m_searchViewModel->applyContextFailure(
                    context.messageId, QStringLiteral("该消息已不可用"));
                return;
            }
            if (!m_viewModel->applyTransientContext(
                    context.conversationId, std::move(messages))) {
                m_searchViewModel->applyContextFailure(
                    context.messageId, QStringLiteral("无法显示消息上下文"));
            } else {
                m_searchViewModel->applyContextAvailable(context.messageId);
            }
            return;
        } catch (...) {
            m_transport->rejectMessagingProtocol();
            return;
        }
    }
    if (m_searchProtocol && (envelope.message_type()
            == chat::v2::MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE
            || (envelope.message_type() == chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR
                && m_searchRequests.contains(requestId)))) {
        const SearchRequest context = m_searchRequests.take(requestId);
        try {
            const auto event = m_searchProtocol->receive(
                std::string(frame.constData(), static_cast<std::size_t>(frame.size())));
            if (event.type == V2WindowsMessageSearchProtocolClient::EventType::ProtocolError) {
                m_searchViewModel->applyFailure(
                    context.conversationId, context.query,
                    QStringLiteral("无法搜索该会话"));
                return;
            }
            QVector<V2WindowsMessageSearchViewModel::Row> rows;
            rows.reserve(static_cast<qsizetype>(event.hits.size()));
            for (const auto &hit : event.hits) {
                rows.append({QString::fromStdString(hit.messageId),
                    hit.conversationSequence,
                    QString::fromStdString(hit.senderAccountId),
                    QString::fromStdString(hit.text), hit.acceptedAtEpochMs,
                    hit.contentRevision, hit.editedAtEpochMs});
            }
            m_searchViewModel->applyPage(
                QString::fromStdString(event.conversationId), context.query,
                std::move(rows), context.continuation,
                event.nextBeforeSequence, event.hasMore);
            return;
        } catch (...) {
            m_transport->rejectMessagingProtocol();
            return;
        }
    }
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
                if (QString::fromStdString(participant.accountId) == m_accountId)
                    continue;
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
    if (outcome.type
            == V2WindowsMessagingApplicationService::OutcomeType::Published
            && outcome.senderAccountId != m_accountId) {
        emit remoteMessagePublished(
            outcome.conversationId, outcome.messageId,
            outcome.senderAccountId,
            outcome.authenticatedAccountMentioned);
    }
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
    if (m_searchProtocol) m_searchProtocol->clearSession();
    if (m_searchContextProtocol) m_searchContextProtocol->clearSession();
    m_searchRequests.clear();
    m_searchContextRequests.clear();
    if (m_viewModel) m_viewModel->clearTransientContext();
    if (m_searchViewModel) m_searchViewModel->setUnavailable();
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
        m_participantProtocol->abandon(command.requestId);
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

bool WindowsV2MessagingController::requestSearch(
        const QString &conversationId, const QString &query,
        quint64 beforeSequence, bool continuation) {
    if (!m_searchProtocol || !m_searchViewModel) return false;
    try {
        const auto command = m_searchProtocol->search(
            conversationId.toStdString(), query.toStdString(), beforeSequence, 20);
        if (m_transport->sendMessagingFrame(QByteArray(
                command.bytes.data(), static_cast<qsizetype>(command.bytes.size())))) {
            m_searchRequests.insert(QString::fromStdString(command.requestId),
                {conversationId, query, continuation});
            return true;
        }
        m_searchProtocol->abandon(command.requestId);
    } catch (...) {}
    return false;
}

bool WindowsV2MessagingController::requestSearchContext(
        const QString &conversationId, quint64 conversationSequence,
        const QString &messageId) {
    if (!m_searchContextProtocol || !m_searchViewModel
            || conversationSequence == 0)
        return false;
    try {
        const auto command = m_searchContextProtocol->readHistory(
            conversationId.toStdString(), conversationSequence - 1, 100);
        if (m_transport->sendMessagingFrame(QByteArray(
                command.bytes.data(), static_cast<qsizetype>(command.bytes.size())))) {
            m_searchContextRequests.insert(QString::fromStdString(command.requestId),
                {conversationId, messageId});
            return true;
        }
        m_searchContextProtocol->abandon(command.requestId);
    } catch (...) {}
    return false;
}
