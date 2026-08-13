#include "V2WindowsMessagingApplicationService.h"

#include <QDateTime>
#include <QUuid>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsMessagingApplicationService::V2WindowsMessagingApplicationService(
        V2LocalMessageRepository *repository, QString accountId, QString deviceId,
        SendFrame sendFrame, Clock clock,
        ClientMessageIdFactory clientMessageIdFactory)
    : m_repository(repository), m_accountId(std::move(accountId)),
      m_deviceId(std::move(deviceId)), m_sendFrame(std::move(sendFrame)),
      m_clock(clock ? std::move(clock) : [] { return QDateTime::currentMSecsSinceEpoch(); }),
      m_clientMessageIdFactory(clientMessageIdFactory
          ? std::move(clientMessageIdFactory) : randomUuid) {
    const auto canonical = [](const QString &value) {
        const QUuid uuid(value);
        return !uuid.isNull() && uuid.toString(QUuid::WithoutBraces) == value;
    };
    if (!m_repository || !canonical(m_accountId) || !canonical(m_deviceId)
            || !m_sendFrame || !m_clock || !m_clientMessageIdFactory)
        throw std::invalid_argument("invalid Windows V2 messaging application service");
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
    m_lastError.clear();
    pumpPending();
    return m_connected;
}

void V2WindowsMessagingApplicationService::disconnectSession() {
    m_protocol.clearSession();
    m_inFlightClientIds.clear();
    m_deferredClientIds.clear();
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
        const QString &text, V2LocalMessageRepository::Message *optimistic) {
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
        const auto command = message.hasReply
            ? m_protocol.submitReplyText(
                message.conversationId.toStdString(), message.clientMessageId.toStdString(),
                message.reply.targetMessageId.toStdString(), message.text.toStdString())
            : m_protocol.submitText(message.conversationId.toStdString(),
                message.clientMessageId.toStdString(), message.text.toStdString());
        if (!sendCommand(command)) return false;
        m_inFlightClientIds.insert(message.clientMessageId);
        return true;
    } catch (const std::exception &exception) {
        m_lastError = QString::fromUtf8(exception.what());
        return false;
    }
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
    result.createdAtEpochMs = message.acceptedAtEpochMs;
    result.state = V2LocalMessageRepository::DeliveryState::Accepted;
    result.hasReply = message.hasReply;
    if (message.hasReply) result.reply = {
        QString::fromStdString(message.reply.targetMessageId),
        static_cast<qint64>(message.reply.targetConversationSequence),
        QString::fromStdString(message.reply.targetSenderAccountId)};
    return result;
}

QString V2WindowsMessagingApplicationService::randomUuid() {
    return QUuid::createUuid().toString(QUuid::WithoutBraces);
}
