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
        bool canForward = false;
        bool canRetry = false;
        bool pinned = false;
        bool pinPending = false;
        bool pinFailed = false;
        QString pinOperationId;
        bool canEdit = false;
        bool edited = false;
        bool forwarded = false;
        bool editPending = false;
        bool editFailed = false;
        bool editConflict = false;
        QString editOperationId;
        QString proposedText;
        QList<V2LocalMessageRepository::Mention> mentions;
        QVector<Reaction> reactions;
    };
    using SnapshotLoader = std::function<V2LocalMessageRepository::Snapshot(const QString &)>;
    using StageText = std::function<bool(
        const QString &, const QString &, V2LocalMessageRepository::Message *,
        const QList<V2LocalMessageRepository::Mention> &)>;
    using StageReply = std::function<bool(
        const QString &, const QString &, const QString &,
        V2LocalMessageRepository::Message *,
        const QList<V2LocalMessageRepository::Mention> &)>;
    using SaveDraft = std::function<bool(const QString &, const QString &)>;
    using Retry = std::function<bool(const QString &, const QString &)>;
    using SetReaction = std::function<bool(const QString &, const QString &,
        V2LocalMessageRepository::ReactionKind)>;
    using RetryReaction = std::function<bool(const QString &, const QString &)>;
    using SetPin = std::function<bool(const QString &, const QString &)>;
    using RetryPin = std::function<bool(const QString &, const QString &)>;
    using Edit = std::function<bool(const QString &, const QString &, const QString &,
        const QList<V2LocalMessageRepository::Mention> &)>;
    using EditOperation = std::function<bool(const QString &, const QString &)>;
    using DiscardEdit = std::function<bool(const QString &)>;
    using StageForward = std::function<bool(
        const QString &, const QString &, const QString &,
        V2LocalMessageRepository::Message *)>;

    V2WindowsMessagingViewModel(
        QString accountId, SnapshotLoader loader, StageText stageText,
        StageReply stageReply, SaveDraft saveDraft,
        Retry retry, SetReaction setReaction, RetryReaction retryReaction,
        SetPin setPin, RetryPin retryPin, Edit edit, EditOperation retryEdit,
        EditOperation rebaseEdit, DiscardEdit discardEdit,
        QObject *parent = nullptr);
    bool openConversation(const QString &conversationId);
    bool refresh();
    bool applyTransientContext(
        const QString &conversationId,
        QList<V2LocalMessageRepository::Message> messages);
    void clearTransientContext();
    QVector<Row> rows() const { return m_rows; }
    QString draft() const { return m_draft; }
    QString replyTargetMessageId() const { return m_replyTargetMessageId; }
    QString replyBanner() const { return m_replyBanner; }
    QString failure() const { return m_failure; }
    bool chooseReply(const QString &messageId);
    void cancelReply();
    bool sendText(const QString &text,
                  const QList<V2LocalMessageRepository::Mention> &mentions = {});
    bool sendReply(const QString &text,
                   const QList<V2LocalMessageRepository::Mention> &mentions = {});
    bool persistDraft(const QString &conversationId, const QString &draft);
    bool retry(const QString &clientMessageId);
    bool setReaction(const QString &messageId, V2LocalMessageRepository::ReactionKind reaction);
    bool retryReaction(const QString &clientOperationId);
    bool setPin(const QString &messageId);
    bool retryPin(const QString &clientOperationId);
    bool editMessage(const QString &messageId, const QString &text,
                     const QList<V2LocalMessageRepository::Mention> &mentions = {});
    bool retryEdit(const QString &clientOperationId);
    bool rebaseEdit(const QString &clientOperationId);
    bool discardEdit(const QString &clientOperationId);
    void configureForwarding(StageForward stageForward);
    bool forwardMessage(const QString &sourceMessageId,
                        const QString &targetConversationId);

signals:
    void changed();
    void focusComposerRequested();

private:
    void project(const V2LocalMessageRepository::Snapshot &snapshot);
    QString previewFor(const V2LocalMessageRepository::Message &message,
                       const V2LocalMessageRepository::Snapshot &snapshot) const;

    QString m_accountId;
    SnapshotLoader m_loader;
    StageText m_stageText;
    StageReply m_stageReply;
    SaveDraft m_saveDraft;
    Retry m_retry;
    SetReaction m_setReaction;
    RetryReaction m_retryReaction;
    SetPin m_setPin;
    RetryPin m_retryPin;
    Edit m_edit;
    EditOperation m_retryEdit;
    EditOperation m_rebaseEdit;
    DiscardEdit m_discardEdit;
    StageForward m_stageForward;
    QString m_conversationId;
    QString m_draft;
    QString m_replyTargetMessageId;
    QString m_replyBanner;
    QString m_failure;
    QVector<Row> m_rows;
    QList<V2LocalMessageRepository::Message> m_transientContext;
};
