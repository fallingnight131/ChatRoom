#pragma once

#include "V2LocalMessageRepository.h"
#include "V2WindowsMessagingProtocolClient.h"

#include <QByteArray>
#include <QSet>
#include <QString>
#include <functional>

class V2WindowsMessagingApplicationService final {
public:
    enum class OutcomeType {
        None, Accepted, HistoryApplied, Published, SendFailed,
        ReactionApplied, ReactionChanged, ReactionFailed,
        PinApplied, PinChanged, PinFailed, Deferred, ProtocolFailure
        , EditApplied, Edited, EditFailed, EditConflict
    };
    struct Outcome {
        OutcomeType type = OutcomeType::None;
        QString conversationId;
        QString messageId;
        QString senderAccountId;
        QString clientMessageId;
        QString clientOperationId;
        bool authenticatedAccountMentioned = false;
    };
    using SendFrame = std::function<bool(const QByteArray &)>;
    using Clock = std::function<qint64()>;
    using ClientMessageIdFactory = std::function<QString()>;

    V2WindowsMessagingApplicationService(
        V2LocalMessageRepository *repository,
        QString accountId,
        QString deviceId,
        SendFrame sendFrame,
        Clock clock = {},
        ClientMessageIdFactory clientMessageIdFactory = {},
        bool enableForwarding = false);

    bool connectSession(const QString &sessionId);
    void disconnectSession();
    bool connected() const { return m_connected; }
    V2LocalMessageRepository::Snapshot hydrate(const QString &conversationId);
    bool stageReply(const QString &conversationId, const QString &targetMessageId,
                    const QString &text,
                    V2LocalMessageRepository::Message *optimistic,
                    const QList<V2LocalMessageRepository::Mention> &mentions = {});
    bool stageForward(const QString &sourceConversationId, const QString &sourceMessageId,
                      const QString &targetConversationId,
                      V2LocalMessageRepository::Message *optimistic);
    bool retry(const QString &conversationId, const QString &clientMessageId);
    bool setReaction(const QString &conversationId, const QString &messageId,
                     V2LocalMessageRepository::ReactionKind reaction);
    bool retryReaction(const QString &conversationId, const QString &clientOperationId);
    bool setPin(const QString &conversationId, const QString &messageId);
    bool retryPin(const QString &conversationId, const QString &clientOperationId);
    bool editMessage(const QString &conversationId, const QString &messageId,
                     const QString &text,
                     const QList<V2LocalMessageRepository::Mention> &mentions = {});
    bool retryEdit(const QString &conversationId, const QString &clientOperationId);
    bool rebaseEdit(const QString &conversationId, const QString &clientOperationId);
    bool discardEdit(const QString &clientOperationId);
    bool requestHistory(const QString &conversationId);
    Outcome receiveFrame(const QByteArray &bytes);
    QString lastError() const { return m_lastError; }

private:
    static QString randomUuid();
    bool dispatch(const V2LocalMessageRepository::Message &message);
    bool dispatchReaction(const V2LocalMessageRepository::ReactionCommand &command);
    bool dispatchPin(const V2LocalMessageRepository::PinCommand &command);
    bool dispatchEdit(const V2LocalMessageRepository::EditCommand &command);
    bool sendCommand(const V2WindowsMessagingProtocolClient::Command &command);
    void pumpPending();
    static V2LocalMessageRepository::Message localMessage(
        const V2WindowsMessagingProtocolClient::Message &message);
    static V2LocalMessageRepository::ReactionChange localReaction(
        const V2WindowsMessagingProtocolClient::ReactionChange &change);
    static V2LocalMessageRepository::PinChange localPin(
        const V2WindowsMessagingProtocolClient::PinChange &change);
    static V2LocalMessageRepository::EditChange localEdit(
        const V2WindowsMessagingProtocolClient::EditChange &change);

    V2LocalMessageRepository *m_repository;
    QString m_accountId;
    QString m_deviceId;
    SendFrame m_sendFrame;
    Clock m_clock;
    ClientMessageIdFactory m_clientMessageIdFactory;
    V2WindowsMessagingProtocolClient m_protocol;
    bool m_enableForwarding = false;
    QSet<QString> m_inFlightClientIds;
    QSet<QString> m_deferredClientIds;
    QSet<QString> m_inFlightReactionIds;
    QSet<QString> m_deferredReactionIds;
    QSet<QString> m_inFlightPinIds;
    QSet<QString> m_deferredPinIds;
    QSet<QString> m_inFlightEditIds;
    QSet<QString> m_deferredEditIds;
    bool m_connected = false;
    QString m_lastError;
};
