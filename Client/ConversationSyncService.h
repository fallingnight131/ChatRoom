#pragma once

#include <QMap>
#include <QString>

#include "LocalConversationRepository.h"

class ConversationSyncService {
public:
    struct ConversationRef {
        LocalConversationRepository::Kind kind = LocalConversationRepository::Kind::Room;
        QString key;
    };
    struct PageProgress {
        qint64 cursor = 0;
        bool requestNext = false;
    };

    explicit ConversationSyncService(
        LocalConversationRepository *repository = nullptr,
        const QString &account = {});

    void setContext(LocalConversationRepository *repository,
                    const QString &account, bool resetCursors);
    LocalConversationRepository::Snapshot hydrate(
        const ConversationRef &conversation);
    qint64 cursor(const ConversationRef &conversation) const;
    qint64 advance(const ConversationRef &conversation, qint64 sequence);
    PageProgress applyPage(const ConversationRef &conversation,
                           bool sequenceMode,
                           const QList<qint64> &observedSequences,
                           qint64 nextSequence, bool hasMore);
    bool replace(const ConversationRef &conversation,
                 const QList<Message> &messages);
    bool upsert(const ConversationRef &conversation, const Message &message);
    bool remove(const ConversationRef &conversation);
    void forget(const ConversationRef &conversation);
    void moveCursor(const ConversationRef &source,
                    const ConversationRef &target);
    bool clearCachedMessages();

    QString lastError() const { return m_lastError; }

private:
    static QString cursorKey(const ConversationRef &conversation);
    bool validate(const ConversationRef &conversation);

    LocalConversationRepository *m_repository = nullptr;
    QString m_account;
    QMap<QString, qint64> m_cursors;
    QString m_lastError;
};
