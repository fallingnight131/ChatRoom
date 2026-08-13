#pragma once

#include "V2LocalMessageRepository.h"

#include <QObject>
#include <QVector>
#include <functional>

class V2WindowsMessagingViewModel final : public QObject {
    Q_OBJECT
public:
    struct Row {
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
    };
    using SnapshotLoader = std::function<V2LocalMessageRepository::Snapshot(const QString &)>;
    using StageReply = std::function<bool(
        const QString &, const QString &, const QString &,
        V2LocalMessageRepository::Message *)>;
    using Retry = std::function<bool(const QString &, const QString &)>;

    V2WindowsMessagingViewModel(
        QString accountId, SnapshotLoader loader, StageReply stageReply,
        Retry retry, QObject *parent = nullptr);
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
    QString m_conversationId;
    QString m_draft;
    QString m_replyTargetMessageId;
    QString m_replyBanner;
    QString m_failure;
    QVector<Row> m_rows;
};
