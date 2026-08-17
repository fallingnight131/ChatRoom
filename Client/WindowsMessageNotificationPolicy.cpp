#include "WindowsMessageNotificationPolicy.h"

#include <stdexcept>

WindowsMessageNotificationPolicy::WindowsMessageNotificationPolicy(
        int rememberedMessageLimit)
    : m_rememberedMessageLimit(rememberedMessageLimit) {
    if (m_rememberedMessageLimit < 1 || m_rememberedMessageLimit > 4096)
        throw std::invalid_argument("invalid Windows notification memory bound");
}

WindowsMessageNotificationPolicy::Decision
WindowsMessageNotificationPolicy::evaluate(
        const IncomingMessage &message, const Visibility &visibility) {
    if (message.messageId.isEmpty() || message.conversationId.isEmpty()
            || message.senderAccountId.isEmpty())
        return {};
    if (m_seenMessageIds.contains(message.messageId)) return {};

    m_seenMessageIds.insert(message.messageId);
    m_seenMessageOrder.enqueue(message.messageId);
    while (m_seenMessageOrder.size() > m_rememberedMessageLimit) {
        m_seenMessageIds.remove(m_seenMessageOrder.dequeue());
    }

    if (visibility.applicationActive
            && visibility.visibleConversationId == message.conversationId)
        return {};

    Decision decision;
    decision.show = true;
    decision.kind = message.authenticatedAccountMentioned
        ? Kind::Mention : Kind::GenericMessage;
    decision.conversationId = message.conversationId;
    return decision;
}

void WindowsMessageNotificationPolicy::clear() {
    m_seenMessageIds.clear();
    m_seenMessageOrder.clear();
}
