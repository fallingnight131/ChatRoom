#include "WindowsMessageNotificationPresenter.h"
#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <stdexcept>
#include <utility>

WindowsMessageNotificationPresenter::WindowsMessageNotificationPresenter(
        Show show, ActivateConversation activateConversation,
        int rememberedMessageLimit, WindowsLocaleViewModel *localeViewModel)
    : m_show(std::move(show)),
      m_activateConversation(std::move(activateConversation)),
      m_policy(rememberedMessageLimit), m_localeViewModel(localeViewModel) {
    if (!m_show || !m_activateConversation)
        throw std::invalid_argument("invalid Windows notification presenter");
}

bool WindowsMessageNotificationPresenter::present(
        const WindowsMessageNotificationPolicy::IncomingMessage &message,
        const WindowsMessageNotificationPolicy::Visibility &visibility) {
    const auto decision = m_policy.evaluate(message, visibility);
    if (!decision.show) return false;
    const WindowsLocale locale = m_localeViewModel
        ? m_localeViewModel->locale() : WindowsLocaleCatalog::defaultLocale();
    const auto &copy = WindowsLocaleCatalog::messages(locale);
    const QString title = decision.kind == WindowsMessageNotificationPolicy::Kind::Mention
        ? copy.notificationMentionedYou : copy.notificationNewMessage;
    return m_show(title, copy.notificationOpenApp, decision.conversationId);
}

bool WindowsMessageNotificationPresenter::activate(
        const QString &conversationId) {
    return !conversationId.isEmpty() && m_activateConversation(conversationId);
}

void WindowsMessageNotificationPresenter::clearSession() {
    m_policy.clear();
}
