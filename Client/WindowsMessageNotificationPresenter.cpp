#include "WindowsMessageNotificationPresenter.h"

#include <stdexcept>
#include <utility>

WindowsMessageNotificationPresenter::WindowsMessageNotificationPresenter(
        Show show, ActivateConversation activateConversation,
        int rememberedMessageLimit)
    : m_show(std::move(show)),
      m_activateConversation(std::move(activateConversation)),
      m_policy(rememberedMessageLimit) {
    if (!m_show || !m_activateConversation)
        throw std::invalid_argument("invalid Windows notification presenter");
}

bool WindowsMessageNotificationPresenter::present(
        const WindowsMessageNotificationPolicy::IncomingMessage &message,
        const WindowsMessageNotificationPolicy::Visibility &visibility) {
    const auto decision = m_policy.evaluate(message, visibility);
    return decision.show
        && m_show(decision.title, decision.body, decision.conversationId);
}

bool WindowsMessageNotificationPresenter::activate(
        const QString &conversationId) {
    return !conversationId.isEmpty() && m_activateConversation(conversationId);
}

void WindowsMessageNotificationPresenter::clearSession() {
    m_policy.clear();
}
