#pragma once

#include "WindowsMessageNotificationPolicy.h"

#include <functional>

class WindowsMessageNotificationPresenter final {
public:
    using Show = std::function<bool(
        const QString &title, const QString &body,
        const QString &conversationId)>;
    using ActivateConversation = std::function<bool(const QString &conversationId)>;

    WindowsMessageNotificationPresenter(
        Show show, ActivateConversation activateConversation,
        int rememberedMessageLimit = 256);

    bool present(
        const WindowsMessageNotificationPolicy::IncomingMessage &message,
        const WindowsMessageNotificationPolicy::Visibility &visibility);
    bool activate(const QString &conversationId);
    void clearSession();

private:
    Show m_show;
    ActivateConversation m_activateConversation;
    WindowsMessageNotificationPolicy m_policy;
};
