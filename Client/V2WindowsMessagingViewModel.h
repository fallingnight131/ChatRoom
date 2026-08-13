#pragma once

#include "V2LocalMessageRepository.h"

#include <QObject>
#include <QVector>
#include <functional>

class V2WindowsMessagingViewModel final : public QObject {
    Q_OBJECT
public:
    struct Row {
        struct Reaction {
            V2LocalMessageRepository::ReactionKind kind = V2LocalMessageRepository::ReactionKind::Like;
            int count = 0;
            bool mine = false;
            bool pending = false;
            bool failed = false;
            QString clientOperationId;
        };
        QString messageId;
        QString clientMessageId;
        QString text;
        QString senderAccountId;
        QString deliveryLabel;
        QString replyPreview;
        bool mine = false;
        bool recalled = false;
        bool canReply = false;
        bool canRetry = false;
        bool pinned = false;
        bool pinPending = false;
        bool pinFailed = false;
        QString pinOperationId;
        QVector<Reaction> reactions;
    };
    using SnapshotLoader = std::function<V2LocalMessageRepository::Snapshot(const QString &)>;
    using StageReply = std::function<bool(
        const QString &, const QString &, const QString &,
        V2LocalMessageRepository::Message *)>;
    using Retry = std::function<bool(const QString &, const QString &)>;
    using SetReaction = std::function<bool(const QString &, const QString &,
        V2LocalMessageRepository::ReactionKind)>;
    using RetryReaction = std::function<bool(const QString &, const QString &)>;
    using SetPin = std::function<bool(const QString &, const QString &)>;
    using RetryPin = std::function<bool(const QString &, const QString &)>;

    V2WindowsMessagingViewModel(
        QString accountId, SnapshotLoader loader, StageReply stageReply,
        Retry retry, SetReaction setReaction, RetryReaction retryReaction,
        SetPin setPin, RetryPin retryPin,
        QObject *parent = nullptr);
    bool openConversation(const QString &conversationId);
    bool refresh();
    QVector<Row> rows() const { return m_rows; }
    QString draft() const { return m_draft; }
    QString replyTargetMessageId() const { return m_replyTargetMessageId; }
    QString replyBanner() const { return m_replyBanner; }
    QString failure() const { return m_failure; }
    bool chooseReply(const QString &messageId);
    void cancelReply();
    bool sendReply(const QString &text);
    bool retry(const QString &clientMessageId);
    bool setReaction(const QString &messageId, V2LocalMessageRepository::ReactionKind reaction);
    bool retryReaction(const QString &clientOperationId);
    bool setPin(const QString &messageId);
    bool retryPin(const QString &clientOperationId);

signals:
    void changed();
    void focusComposerRequested();

private:
    void project(const V2LocalMessageRepository::Snapshot &snapshot);
    QString previewFor(const V2LocalMessageRepository::Message &message,
                       const V2LocalMessageRepository::Snapshot &snapshot) const;

    QString m_accountId;
    SnapshotLoader m_loader;
    StageReply m_stageReply;
    Retry m_retry;
    SetReaction m_setReaction;
    RetryReaction m_retryReaction;
    SetPin m_setPin;
    RetryPin m_retryPin;
    QString m_conversationId;
    QString m_draft;
    QString m_replyTargetMessageId;
    QString m_replyBanner;
    QString m_failure;
    QVector<Row> m_rows;
};
