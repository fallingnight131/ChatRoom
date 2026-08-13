#include "V2WindowsMessagingApplicationService.h"
#include "chat/v2/control.pb.h"

#include <QDateTime>
#include <QUuid>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsMessagingApplicationService::V2WindowsMessagingApplicationService(
        V2LocalMessageRepository *repository, QString accountId, QString deviceId,
        SendFrame sendFrame, Clock clock,
        ClientMessageIdFactory clientMessageIdFactory, bool enableForwarding)
    : m_repository(repository), m_accountId(std::move(accountId)),
      m_deviceId(std::move(deviceId)), m_sendFrame(std::move(sendFrame)),
      m_clock(clock ? std::move(clock) : [] { return QDateTime::currentMSecsSinceEpoch(); }),
      m_clientMessageIdFactory(clientMessageIdFactory
          ? std::move(clientMessageIdFactory) : randomUuid),
      m_protocol({}, {}, enableForwarding), m_enableForwarding(enableForwarding) {
    const auto canonical = [](const QString &value) {
        const QUuid uuid(value);
        return !uuid.isNull() && uuid.toString(QUuid::WithoutBraces) == value;
    };
    if (!m_repository || !canonical(m_accountId) || !canonical(m_deviceId)
            || !m_sendFrame || !m_clock || !m_clientMessageIdFactory)
        throw std::invalid_argument("invalid Windows V2 messaging application service");
}

bool V2WindowsMessagingApplicationService::stageForward(
        const QString &sourceConversationId, const QString &sourceMessageId,
        const QString &targetConversationId, V2LocalMessageRepository::Message *optimistic) {
    m_lastError.clear();
    if (!m_enableForwarding) {
        m_lastError = QStringLiteral("message forwarding is not enabled"); return false;
    }
    if (!optimistic) { m_lastError = QStringLiteral("missing forward output"); return false; }
    const auto sourceSnapshot = hydrate(sourceConversationId);
    if (!m_lastError.isEmpty()) return false;
    const auto source = std::find_if(sourceSnapshot.messages.cbegin(), sourceSnapshot.messages.cend(),
        [&](const auto &message) { return message.messageId == sourceMessageId
            && message.state == V2LocalMessageRepository::DeliveryState::Accepted
            && !message.recalled; });
    if (source == sourceSnapshot.messages.cend()) {
        m_lastError = QStringLiteral("forward source is unavailable"); return false;
    }
    V2LocalMessageRepository::Message message;
    message.conversationId = targetConversationId; message.senderAccountId = m_accountId;
    message.senderDeviceId = m_deviceId; message.clientMessageId = m_clientMessageIdFactory();
    message.text = source->text; message.createdAtEpochMs = m_clock();
    message.state = V2LocalMessageRepository::DeliveryState::Pending;
    message.forwarded = true; message.forwardSourceConversationId = sourceConversationId;
    message.forwardSourceMessageId = sourceMessageId;
    message.expectedForwardSourceRevision = source->contentRevision;
    if (!m_repository->upsertPending(m_accountId, message)) {
        m_lastError = m_repository->lastError(); return false;
    }
    *optimistic = message;
    if (m_connected) dispatch(message);
    return true;
}

bool V2WindowsMessagingApplicationService::connectSession(const QString &sessionId) {
    try {
        m_protocol.bindSession(sessionId.toStdString());
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        return false;
    }
    m_connected = true;
    m_inFlightClientIds.clear();
    m_deferredClientIds.clear();
    m_inFlightReactionIds.clear();
    m_deferredReactionIds.clear();
    m_inFlightPinIds.clear();
    m_deferredPinIds.clear();
    m_inFlightEditIds.clear();
    m_deferredEditIds.clear();
    m_lastError.clear();
    pumpPending();
    return m_connected;
}

void V2WindowsMessagingApplicationService::disconnectSession() {
    m_protocol.clearSession();
    m_inFlightClientIds.clear();
    m_deferredClientIds.clear();
    m_inFlightReactionIds.clear();
    m_deferredReactionIds.clear();
    m_inFlightPinIds.clear();
    m_deferredPinIds.clear();
    m_inFlightEditIds.clear();
    m_deferredEditIds.clear();
    m_connected = false;
}

V2LocalMessageRepository::Snapshot
V2WindowsMessagingApplicationService::hydrate(const QString &conversationId) {
    auto snapshot = m_repository->loadSnapshot(m_accountId, conversationId);
    m_lastError = m_repository->lastError();
    return snapshot;
}

bool V2WindowsMessagingApplicationService::stageReply(
        const QString &conversationId, const QString &targetMessageId,
        const QString &text, V2LocalMessageRepository::Message *optimistic,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    m_lastError.clear();
    if (!optimistic || text.isEmpty()) {
        m_lastError = QStringLiteral("missing reply output or content");
        return false;
    }
    const auto snapshot = hydrate(conversationId);
    if (!m_lastError.isEmpty()) return false;
    const auto target = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &message) {
            return message.messageId == targetMessageId
                && message.state == V2LocalMessageRepository::DeliveryState::Accepted
                && !message.recalled;
        });
    if (target == snapshot.messages.cend()) {
        m_lastError = QStringLiteral("reply target is unavailable");
        return false;
    }
    V2LocalMessageRepository::Message message;
    message.conversationId = conversationId;
    message.senderAccountId = m_accountId;
    message.senderDeviceId = m_deviceId;
    message.clientMessageId = m_clientMessageIdFactory();
    message.text = text;
    message.createdAtEpochMs = m_clock();
    message.state = V2LocalMessageRepository::DeliveryState::Pending;
    message.mentions = mentions;
    message.hasReply = true;
    message.reply = {target->messageId, target->conversationSequence, target->senderAccountId};
    if (!m_repository->upsertPending(m_accountId, message)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    *optimistic = message;
    if (m_connected) dispatch(message);
    return true;
}

bool V2WindowsMessagingApplicationService::retry(
        const QString &conversationId, const QString &clientMessageId) {
    m_lastError.clear();
    auto snapshot = hydrate(conversationId);
    if (!m_lastError.isEmpty()) return false;
    const auto position = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &message) {
            return message.clientMessageId == clientMessageId
                && message.state == V2LocalMessageRepository::DeliveryState::Failed;
        });
    if (position == snapshot.messages.cend()) {
        m_lastError = QStringLiteral("failed reply is unavailable");
        return false;
    }
    auto message = *position;
    message.state = V2LocalMessageRepository::DeliveryState::Pending;
    if (!m_repository->upsertPending(m_accountId, message)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    m_deferredClientIds.remove(clientMessageId);
    if (m_connected) dispatch(message);
    return true;
}

bool V2WindowsMessagingApplicationService::setReaction(
        const QString &conversationId, const QString &messageId,
        V2LocalMessageRepository::ReactionKind reaction) {
    m_lastError.clear();
    const auto snapshot = hydrate(conversationId);
    const auto target = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &message) { return message.messageId == messageId
            && message.state == V2LocalMessageRepository::DeliveryState::Accepted
            && !message.recalled; });
    if (target == snapshot.messages.cend()) { m_lastError = QStringLiteral("reaction target unavailable"); return false; }
    const auto aggregate = std::find_if(target->reactions.cbegin(), target->reactions.cend(),
        [&](const auto &value) { return value.reaction == reaction; });
    const bool active = aggregate == target->reactions.cend()
        || !aggregate->actorAccountIds.contains(m_accountId);
    V2LocalMessageRepository::ReactionCommand command;
    command.conversationId = conversationId; command.messageId = messageId;
    command.reaction = reaction; command.active = active;
    command.clientOperationId = m_clientMessageIdFactory();
    if (!m_repository->stageReaction(m_accountId, command)) {
        m_lastError = m_repository->lastError(); return false;
    }
    if (m_connected) dispatchReaction(command);
    return true;
}

bool V2WindowsMessagingApplicationService::retryReaction(
        const QString &conversationId, const QString &clientOperationId) {
    auto snapshot = hydrate(conversationId);
    const auto position = std::find_if(snapshot.reactionCommands.cbegin(),
        snapshot.reactionCommands.cend(), [&](const auto &command) {
            return command.clientOperationId == clientOperationId
                && command.state == V2LocalMessageRepository::DeliveryState::Failed;
        });
    if (position == snapshot.reactionCommands.cend()) return false;
    auto command = *position; command.state = V2LocalMessageRepository::DeliveryState::Pending;
    if (!m_repository->stageReaction(m_accountId, command)) {
        m_lastError = m_repository->lastError(); return false;
    }
    m_deferredReactionIds.remove(clientOperationId);
    if (m_connected) dispatchReaction(command);
    return true;
}

bool V2WindowsMessagingApplicationService::setPin(
        const QString &conversationId, const QString &messageId) {
    m_lastError.clear();
    const auto snapshot = hydrate(conversationId);
    const auto target = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &message) {
            return message.messageId == messageId
                && message.state == V2LocalMessageRepository::DeliveryState::Accepted
                && !message.recalled;
        });
    if (target == snapshot.messages.cend()) {
        m_lastError = QStringLiteral("pin target unavailable");
        return false;
    }
    V2LocalMessageRepository::PinCommand command;
    command.conversationId = conversationId;
    command.messageId = messageId;
    command.pinned = !target->pinned;
    command.clientOperationId = m_clientMessageIdFactory();
    if (!m_repository->stagePin(m_accountId, command)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    if (m_connected) dispatchPin(command);
    return true;
}

bool V2WindowsMessagingApplicationService::retryPin(
        const QString &conversationId, const QString &clientOperationId) {
    auto snapshot = hydrate(conversationId);
    const auto position = std::find_if(snapshot.pinCommands.cbegin(),
        snapshot.pinCommands.cend(), [&](const auto &command) {
            return command.clientOperationId == clientOperationId
                && command.state == V2LocalMessageRepository::DeliveryState::Failed;
        });
    if (position == snapshot.pinCommands.cend()) return false;
    auto command = *position;
    command.state = V2LocalMessageRepository::DeliveryState::Pending;
    if (!m_repository->stagePin(m_accountId, command)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    m_deferredPinIds.remove(clientOperationId);
    if (m_connected) dispatchPin(command);
    return true;
}

bool V2WindowsMessagingApplicationService::editMessage(
        const QString &conversationId, const QString &messageId, const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    m_lastError.clear(); const auto snapshot=hydrate(conversationId);
    const auto target=std::find_if(snapshot.messages.cbegin(),snapshot.messages.cend(),
        [&](const auto &message){ return message.messageId==messageId && message.senderAccountId==m_accountId
            && message.state==V2LocalMessageRepository::DeliveryState::Accepted && !message.recalled; });
    if (target==snapshot.messages.cend() || text.isEmpty() || text==target->text) {
        m_lastError=QStringLiteral("edit target unavailable"); return false;
    }
    V2LocalMessageRepository::EditCommand command{conversationId,messageId,target->contentRevision,
        text,m_clientMessageIdFactory(),V2LocalMessageRepository::EditDeliveryState::Pending,mentions};
    if (!m_repository->stageEdit(m_accountId,command)) { m_lastError=m_repository->lastError(); return false; }
    if (m_connected) dispatchEdit(command); return true;
}

bool V2WindowsMessagingApplicationService::retryEdit(
        const QString &conversationId, const QString &operationId) {
    auto snapshot=hydrate(conversationId); const auto position=std::find_if(snapshot.editCommands.cbegin(),
        snapshot.editCommands.cend(),[&](const auto &command){ return command.clientOperationId==operationId
            && command.state==V2LocalMessageRepository::EditDeliveryState::Failed; });
    if (position==snapshot.editCommands.cend()) return false; auto command=*position;
    command.state=V2LocalMessageRepository::EditDeliveryState::Pending;
    if (!m_repository->stageEdit(m_accountId,command)) { m_lastError=m_repository->lastError(); return false; }
    m_deferredEditIds.remove(operationId); if (m_connected) dispatchEdit(command); return true;
}

bool V2WindowsMessagingApplicationService::rebaseEdit(
        const QString &conversationId, const QString &operationId) {
    auto snapshot=hydrate(conversationId); const auto position=std::find_if(snapshot.editCommands.cbegin(),
        snapshot.editCommands.cend(),[&](const auto &command){ return command.clientOperationId==operationId
            && command.state==V2LocalMessageRepository::EditDeliveryState::Conflict; });
    if (position==snapshot.editCommands.cend()) return false;
    const auto message=std::find_if(snapshot.messages.cbegin(),snapshot.messages.cend(),
        [&](const auto &value){ return value.messageId==position->messageId; });
    if (message==snapshot.messages.cend() || message->contentRevision<=position->expectedRevision) return false;
    auto replacement=*position; replacement.expectedRevision=message->contentRevision;
    replacement.clientOperationId=m_clientMessageIdFactory(); replacement.state=V2LocalMessageRepository::EditDeliveryState::Pending;
    if (!m_repository->rebaseEdit(m_accountId,operationId,replacement)) { m_lastError=m_repository->lastError(); return false; }
    m_deferredEditIds.remove(operationId); if (m_connected) dispatchEdit(replacement); return true;
}

bool V2WindowsMessagingApplicationService::discardEdit(const QString &operationId) {
    m_inFlightEditIds.remove(operationId); m_deferredEditIds.remove(operationId);
    if (!m_repository->discardEdit(m_accountId,operationId)) { m_lastError=m_repository->lastError(); return false; }
    return true;
}

bool V2WindowsMessagingApplicationService::requestHistory(const QString &conversationId) {
    m_lastError.clear();
    if (!m_connected) { m_lastError = QStringLiteral("messaging session is offline"); return false; }
    const auto snapshot = hydrate(conversationId);
    if (!m_lastError.isEmpty()) return false;
    try {
        return sendCommand(m_protocol.readHistory(
            conversationId.toStdString(), static_cast<std::uint64_t>(snapshot.cursor), 100));
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        return false;
    }
}

V2WindowsMessagingApplicationService::Outcome
V2WindowsMessagingApplicationService::receiveFrame(const QByteArray &bytes) {
    Outcome result;
    if (!m_connected) return result;
    V2WindowsMessagingProtocolClient::Event event;
    try {
        event = m_protocol.receive(std::string(bytes.constData(), static_cast<std::size_t>(bytes.size())));
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        disconnectSession();
        result.type = OutcomeType::ProtocolFailure;
        return result;
    }
    result.conversationId = QString::fromStdString(event.conversationId);
    result.clientMessageId = QString::fromStdString(event.clientMessageId);
    result.clientOperationId = QString::fromStdString(
        !event.editChange.clientOperationId.empty() ? event.editChange.clientOperationId
        : !event.pinChange.clientOperationId.empty()
            ? event.pinChange.clientOperationId : event.reactionChange.clientOperationId);
    if (event.type == V2WindowsMessagingProtocolClient::EventType::Accepted) {
        if (!m_repository->applyAccepted(
                m_accountId, result.conversationId, result.clientMessageId,
                QString::fromStdString(event.messageId),
                static_cast<qint64>(event.conversationSequence), event.acceptedAtEpochMs)) {
            m_lastError = m_repository->lastError();
            disconnectSession(); result.type = OutcomeType::ProtocolFailure; return result;
        }
        m_inFlightClientIds.remove(result.clientMessageId);
        result.type = OutcomeType::Accepted;
        pumpPending();
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::ProtocolError) {
        if (!result.clientOperationId.isEmpty()) {
            const bool isEdit = !event.editChange.clientOperationId.empty();
            const bool isPin = !event.pinChange.clientOperationId.empty();
            auto &inFlight = isEdit ? m_inFlightEditIds : isPin ? m_inFlightPinIds : m_inFlightReactionIds;
            auto &deferred = isEdit ? m_deferredEditIds : isPin ? m_deferredPinIds : m_deferredReactionIds;
            inFlight.remove(result.clientOperationId);
            if (event.retryable) {
                deferred.insert(result.clientOperationId);
                result.type = OutcomeType::Deferred;
            } else if (isEdit) {
                const bool conflict=event.protocolErrorCode==chat::v2::PROTOCOL_ERROR_CODE_MESSAGE_REVISION_CONFLICT;
                if (!m_repository->markEditFailed(m_accountId,result.clientOperationId,conflict)) {
                    m_lastError=m_repository->lastError(); disconnectSession(); result.type=OutcomeType::ProtocolFailure;
                } else { result.type=conflict ? OutcomeType::EditConflict : OutcomeType::EditFailed;
                    if (conflict) requestHistory(result.conversationId); }
            } else if (!(isPin
                    ? m_repository->markPinFailed(m_accountId, result.clientOperationId)
                    : m_repository->markReactionFailed(m_accountId, result.clientOperationId))) {
                m_lastError = m_repository->lastError(); disconnectSession();
                result.type = OutcomeType::ProtocolFailure;
            } else {
                result.type = isPin ? OutcomeType::PinFailed : OutcomeType::ReactionFailed;
            }
            pumpPending(); return result;
        }
        if (!result.clientMessageId.isEmpty()) {
            m_inFlightClientIds.remove(result.clientMessageId);
            if (event.retryable) {
                m_deferredClientIds.insert(result.clientMessageId);
                result.type = OutcomeType::Deferred;
            } else {
                if (!m_repository->markFailed(
                        m_accountId, result.conversationId, result.clientMessageId)) {
                    m_lastError = m_repository->lastError();
                    disconnectSession();
                    result.type = OutcomeType::ProtocolFailure;
                    return result;
                }
                result.type = OutcomeType::SendFailed;
            }
        } else {
            result.type = OutcomeType::SendFailed;
        }
        pumpPending();
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::EditApplied) {
        if (QString::fromStdString(event.editChange.actorAccountId)!=m_accountId
                || !m_repository->applyEdit(m_accountId,localEdit(event.editChange))) {
            m_lastError=m_repository->lastError(); disconnectSession(); result.type=OutcomeType::ProtocolFailure;
        } else { m_inFlightEditIds.remove(result.clientOperationId); result.type=OutcomeType::EditApplied; pumpPending(); }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::Edited) {
        if (!m_repository->mergeLiveEdit(m_accountId,localEdit(event.editChange))) {
            m_lastError=m_repository->lastError(); disconnectSession(); result.type=OutcomeType::ProtocolFailure;
        } else { result.type=OutcomeType::Edited; requestHistory(result.conversationId); }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::ReactionApplied) {
        if (QString::fromStdString(event.reactionChange.actorAccountId) != m_accountId) {
            m_lastError = QStringLiteral("reaction actor differs from authenticated account");
            disconnectSession(); result.type = OutcomeType::ProtocolFailure; return result;
        }
        if (!m_repository->applyReaction(m_accountId, localReaction(event.reactionChange))) {
            m_lastError = m_repository->lastError(); disconnectSession();
            result.type = OutcomeType::ProtocolFailure;
        } else {
            m_inFlightReactionIds.remove(result.clientOperationId);
            result.type = OutcomeType::ReactionApplied; pumpPending();
        }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::ReactionChanged) {
        if (!m_repository->mergeLiveReaction(m_accountId, localReaction(event.reactionChange))) {
            m_lastError = m_repository->lastError(); disconnectSession();
            result.type = OutcomeType::ProtocolFailure;
        } else {
            result.type = OutcomeType::ReactionChanged;
            requestHistory(result.conversationId);
        }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::PinApplied) {
        if (QString::fromStdString(event.pinChange.actorAccountId) != m_accountId) {
            m_lastError = QStringLiteral("pin actor differs from authenticated account");
            disconnectSession(); result.type = OutcomeType::ProtocolFailure; return result;
        }
        if (!m_repository->applyPin(m_accountId, localPin(event.pinChange))) {
            m_lastError = m_repository->lastError(); disconnectSession();
            result.type = OutcomeType::ProtocolFailure;
        } else {
            m_inFlightPinIds.remove(result.clientOperationId);
            result.type = OutcomeType::PinApplied; pumpPending();
        }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::PinChanged) {
        if (!m_repository->mergeLivePin(m_accountId, localPin(event.pinChange))) {
            m_lastError = m_repository->lastError(); disconnectSession();
            result.type = OutcomeType::ProtocolFailure;
        } else {
            result.type = OutcomeType::PinChanged;
            requestHistory(result.conversationId);
        }
        return result;
    }
    if (event.type == V2WindowsMessagingProtocolClient::EventType::Published) {
        const auto message = localMessage(event.messages.front());
        if (!m_repository->mergeLiveMessage(m_accountId, message)) {
            m_lastError = m_repository->lastError();
            disconnectSession();
            result.type = OutcomeType::ProtocolFailure;
        } else {
            result.type = OutcomeType::Published;
            result.conversationId = message.conversationId;
        }
        return result;
    }
    QList<V2LocalMessageRepository::Message> messages;
    for (const auto &message : event.messages) messages.append(localMessage(message));
    if (!m_repository->mergeServerPage(
            m_accountId, result.conversationId, messages,
            static_cast<qint64>(event.nextSequence),
            [&] {
                QStringList values;
                for (const auto &id : event.recalledMessageIds)
                    values.append(QString::fromStdString(id));
                return values;
            }(),
            [&] {
                QStringList values;
                for (const auto &id : event.deletedMessageIds)
                    values.append(QString::fromStdString(id));
                return values;
            }(),
            [&] {
                QList<V2LocalMessageRepository::ReactionChange> values;
                for (const auto &change : event.reactionChanges)
                    values.append(localReaction(change));
                return values;
            }(),
            [&] {
                QList<V2LocalMessageRepository::PinChange> values;
                for (const auto &change : event.pinChanges)
                    values.append(localPin(change));
                return values;
            }(),
            [&] {
                QList<V2LocalMessageRepository::EditChange> values;
                for (const auto &change : event.editChanges) values.append(localEdit(change));
                return values;
            }())) {
        m_lastError = m_repository->lastError();
        disconnectSession();
        result.type = OutcomeType::ProtocolFailure;
        return result;
    }
    result.type = OutcomeType::HistoryApplied;
    if (event.hasMore) requestHistory(result.conversationId);
    return result;
}

bool V2WindowsMessagingApplicationService::dispatch(
        const V2LocalMessageRepository::Message &message) {
    if (!m_connected || m_inFlightClientIds.contains(message.clientMessageId)
            || m_deferredClientIds.contains(message.clientMessageId)) return m_connected;
    try {
        std::vector<V2WindowsMessagingProtocolClient::Mention> mentions;
        mentions.reserve(static_cast<std::size_t>(message.mentions.size()));
        for (const auto &mention : message.mentions)
            mentions.push_back({mention.targetAccountId.toStdString(),
                static_cast<std::uint32_t>(mention.startUtf8Byte),
                static_cast<std::uint32_t>(mention.lengthUtf8Bytes)});
        const auto command = !message.forwardSourceConversationId.isEmpty()
            ? m_protocol.forwardMessage(
                message.forwardSourceConversationId.toStdString(),
                message.forwardSourceMessageId.toStdString(),
                static_cast<std::uint32_t>(message.expectedForwardSourceRevision),
                message.conversationId.toStdString(), message.clientMessageId.toStdString())
            : message.hasReply ? m_protocol.submitReplyText(
                message.conversationId.toStdString(), message.clientMessageId.toStdString(),
                message.reply.targetMessageId.toStdString(), message.text.toStdString(), mentions)
            : m_protocol.submitText(message.conversationId.toStdString(),
                message.clientMessageId.toStdString(), message.text.toStdString(), mentions);
        if (!sendCommand(command)) return false;
        m_inFlightClientIds.insert(message.clientMessageId);
        return true;
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        return false;
    }
}

bool V2WindowsMessagingApplicationService::dispatchReaction(
        const V2LocalMessageRepository::ReactionCommand &command) {
    if (!m_connected || m_inFlightReactionIds.contains(command.clientOperationId)
            || m_deferredReactionIds.contains(command.clientOperationId)) return m_connected;
    try {
        const auto wireKind = static_cast<V2WindowsMessagingProtocolClient::ReactionKind>(
            static_cast<int>(command.reaction));
        if (!sendCommand(m_protocol.setReaction(command.conversationId.toStdString(),
                command.messageId.toStdString(), wireKind, command.active,
                command.clientOperationId.toStdString()))) return false;
        m_inFlightReactionIds.insert(command.clientOperationId);
        return true;
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what()); return false;
    }
}

bool V2WindowsMessagingApplicationService::dispatchPin(
        const V2LocalMessageRepository::PinCommand &command) {
    if (!m_connected || m_inFlightPinIds.contains(command.clientOperationId)
            || m_deferredPinIds.contains(command.clientOperationId)) return m_connected;
    try {
        if (!sendCommand(m_protocol.setPin(command.conversationId.toStdString(),
                command.messageId.toStdString(), command.pinned,
                command.clientOperationId.toStdString()))) return false;
        m_inFlightPinIds.insert(command.clientOperationId);
        return true;
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        return false;
    }
}

bool V2WindowsMessagingApplicationService::dispatchEdit(
        const V2LocalMessageRepository::EditCommand &command) {
    if (!m_connected || m_inFlightEditIds.contains(command.clientOperationId)
            || m_deferredEditIds.contains(command.clientOperationId)) return m_connected;
    try {
        std::vector<V2WindowsMessagingProtocolClient::Mention> mentions;
        mentions.reserve(static_cast<std::size_t>(command.mentions.size()));
        for (const auto &mention : command.mentions)
            mentions.push_back({mention.targetAccountId.toStdString(),
                static_cast<std::uint32_t>(mention.startUtf8Byte),
                static_cast<std::uint32_t>(mention.lengthUtf8Bytes)});
        if (!sendCommand(m_protocol.editMessage(command.conversationId.toStdString(),
                command.messageId.toStdString(),static_cast<std::uint32_t>(command.expectedRevision),
                command.proposedText.toStdString(),command.clientOperationId.toStdString(),
                mentions))) return false;
        m_inFlightEditIds.insert(command.clientOperationId); return true;
    } catch (const std::exception &exception) { m_lastError=QString::fromUtf8(exception.what()); return false; }
}

bool V2WindowsMessagingApplicationService::sendCommand(
        const V2WindowsMessagingProtocolClient::Command &command) {
    const QByteArray frame(command.bytes.data(), static_cast<qsizetype>(command.bytes.size()));
    if (m_sendFrame(frame)) return true;
    m_lastError = QStringLiteral("transport rejected V2 messaging frame");
    disconnectSession();
    return false;
}

void V2WindowsMessagingApplicationService::pumpPending() {
    if (!m_connected) return;
    const auto messages = m_repository->pendingSends(m_accountId);
    for (const auto &message : messages) {
        if (!m_connected || m_protocol.pendingCount() >= 32) break;
        if (!dispatch(message) && !m_connected) break;
    }
    for (const auto &reaction : m_repository->pendingReactions(m_accountId)) {
        if (!m_connected || m_protocol.pendingCount() >= 32) break;
        if (!dispatchReaction(reaction) && !m_connected) break;
    }
    for (const auto &pin : m_repository->pendingPins(m_accountId)) {
        if (!m_connected || m_protocol.pendingCount() >= 32) break;
        if (!dispatchPin(pin) && !m_connected) break;
    }
    for (const auto &edit : m_repository->pendingEdits(m_accountId)) {
        if (!m_connected || m_protocol.pendingCount()>=32) break;
        if (!dispatchEdit(edit) && !m_connected) break;
    }
}

V2LocalMessageRepository::Message
V2WindowsMessagingApplicationService::localMessage(
        const V2WindowsMessagingProtocolClient::Message &message) {
    V2LocalMessageRepository::Message result;
    result.conversationId = QString::fromStdString(message.conversationId);
    result.messageId = QString::fromStdString(message.messageId);
    result.conversationSequence = static_cast<qint64>(message.conversationSequence);
    result.senderAccountId = QString::fromStdString(message.senderAccountId);
    result.senderDeviceId = QString::fromStdString(message.senderDeviceId);
    result.clientMessageId = QString::fromStdString(message.clientMessageId);
    result.text = QString::fromStdString(message.text);
    result.acceptedAtEpochMs = message.acceptedAtEpochMs;
    result.contentRevision = static_cast<int>(message.contentRevision);
    result.editedAtEpochMs = message.editedAtEpochMs;
    result.createdAtEpochMs = message.acceptedAtEpochMs;
    result.state = V2LocalMessageRepository::DeliveryState::Accepted;
    result.hasReply = message.hasReply;
    result.forwarded = message.forwarded;
    for (const auto &mention : message.mentions)
        result.mentions.append({QString::fromStdString(mention.targetAccountId),
            static_cast<int>(mention.startUtf8Byte),
            static_cast<int>(mention.lengthUtf8Bytes)});
    if (message.hasReply) result.reply = {
        QString::fromStdString(message.reply.targetMessageId),
        static_cast<qint64>(message.reply.targetConversationSequence),
        QString::fromStdString(message.reply.targetSenderAccountId)};
    return result;
}

V2LocalMessageRepository::ReactionChange
V2WindowsMessagingApplicationService::localReaction(
        const V2WindowsMessagingProtocolClient::ReactionChange &change) {
    return {QString::fromStdString(change.conversationId),
        static_cast<qint64>(change.conversationSequence),
        QString::fromStdString(change.messageId),
        static_cast<V2LocalMessageRepository::ReactionKind>(static_cast<int>(change.reaction)),
        change.active, QString::fromStdString(change.actorAccountId),
        QString::fromStdString(change.clientOperationId), change.occurredAtEpochMs};
}

V2LocalMessageRepository::PinChange
V2WindowsMessagingApplicationService::localPin(
        const V2WindowsMessagingProtocolClient::PinChange &change) {
    return {QString::fromStdString(change.conversationId),
        static_cast<qint64>(change.conversationSequence),
        QString::fromStdString(change.messageId), change.pinned,
        QString::fromStdString(change.actorAccountId),
        QString::fromStdString(change.clientOperationId), change.occurredAtEpochMs};
}

V2LocalMessageRepository::EditChange
V2WindowsMessagingApplicationService::localEdit(
        const V2WindowsMessagingProtocolClient::EditChange &change) {
    V2LocalMessageRepository::EditChange result{
        QString::fromStdString(change.conversationId),static_cast<qint64>(change.conversationSequence),
        QString::fromStdString(change.messageId),static_cast<int>(change.contentRevision),
        QString::fromStdString(change.text),QString::fromStdString(change.actorAccountId),
        QString::fromStdString(change.clientOperationId),change.occurredAtEpochMs,{}};
    for (const auto &mention : change.mentions)
        result.mentions.append({QString::fromStdString(mention.targetAccountId),
            static_cast<int>(mention.startUtf8Byte),
            static_cast<int>(mention.lengthUtf8Bytes)});
    return result;
}

QString V2WindowsMessagingApplicationService::randomUuid() {
    return QUuid::createUuid().toString(QUuid::WithoutBraces);
}
