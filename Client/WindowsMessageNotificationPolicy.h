#pragma once

#include <QQueue>
#include <QSet>
#include <QString>

class WindowsMessageNotificationPolicy final {
public:
    struct IncomingMessage {
        QString messageId;
        QString conversationId;
        QString senderAccountId;
        bool authenticatedAccountMentioned = false;
    };

    struct Visibility {
        bool applicationActive = false;
        QString visibleConversationId;
    };

    struct Decision {
        bool show = false;
        QString title;
        QString body;
        QString conversationId;
    };

    explicit WindowsMessageNotificationPolicy(int rememberedMessageLimit = 256);

    Decision evaluate(const IncomingMessage &message, const Visibility &visibility);
    void clear();
    int rememberedMessageCount() const { return m_seenMessageIds.size(); }

private:
    int m_rememberedMessageLimit;
    QSet<QString> m_seenMessageIds;
    QQueue<QString> m_seenMessageOrder;
};
