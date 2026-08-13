#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"

#include <QApplication>
#include <QListWidget>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTimer>
#include <QDebug>
#include <algorithm>

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    const QString account = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message message;
    message.conversationId = conversation;
    message.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    message.senderAccountId = QStringLiteral("10000000-0000-4000-8000-000000000002");
    message.clientMessageId = QStringLiteral("remote-1");
    message.text = QStringLiteral("hello");
    message.state = V2LocalMessageRepository::DeliveryState::Accepted;
    snapshot.messages.append(message);
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &, const QString &,
            V2LocalMessageRepository::Message *) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, V2LocalMessageRepository::ReactionKind) {
            return true;
        },
        [](const QString &, const QString &) { return true; });
    model.openConversation(conversation);
    V2WindowsMessagingPanel panel(&model);
    panel.show();
    if (panel.accessibleName().isEmpty()
            || panel.messageListForTest()->accessibleName().isEmpty()
            || panel.composerForTest()->accessibleName().isEmpty()
            || panel.sendForTest()->accessibleName().isEmpty()) return 1;
    auto replies = panel.findChildren<QPushButton *>(QString(), Qt::FindChildrenRecursively);
    auto reply = std::find_if(replies.cbegin(), replies.cend(), [](QPushButton *button) {
        return button->text() == QStringLiteral("回复");
    });
    if (reply == replies.cend()) return 1;
    const auto reactionButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isCheckable()
                && button->accessibleName().startsWith(QStringLiteral("消息反应"));
        });
    if (reactionButtons != 6) return 1;
    (*reply)->click();
    app.processEvents();
    if (!panel.cancelReplyForTest()->isVisible()
            || model.replyTargetMessageId() != message.messageId) return 1;
    panel.composerForTest()->setPlainText(QStringLiteral("reply text"));
    app.processEvents();
    if (!panel.sendForTest()->isEnabled()) return 1;
    panel.cancelReplyForTest()->click();
    app.processEvents();
    if (!model.replyTargetMessageId().isEmpty()) return 1;
    qInfo() << "[V2WindowsMessagingPanelTest] PASS";
    return 0;
}
