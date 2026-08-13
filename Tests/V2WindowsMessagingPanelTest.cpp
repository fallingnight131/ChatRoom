#include "V2WindowsMessagingPanel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsForwardTargetDialog.h"
#include "V2WindowsMessagingViewModel.h"

#include <QApplication>
#include <QLabel>
#include <QListWidget>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTextCursor>
#include <QTimer>
#include <QDebug>
#include <algorithm>

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    const QString account = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString mentionTarget = QStringLiteral("10000000-0000-4000-8000-000000000002");
    const QString conversation = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message message;
    message.conversationId = conversation;
    message.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    message.senderAccountId = account;
    message.clientMessageId = QStringLiteral("remote-1");
    message.text = QStringLiteral("@张三😀 hello <b>x</b>");
    message.mentions.append({mentionTarget, 0,
        static_cast<int>(QStringLiteral("@张三😀").toUtf8().size())});
    message.state = V2LocalMessageRepository::DeliveryState::Accepted;
    message.forwarded = true;
    snapshot.messages.append(message);
    QList<V2LocalMessageRepository::Mention> submittedMentions;
    QList<V2LocalMessageRepository::Mention> editedMentions;
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &, const QString &,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
                submittedMentions = mentions;
                return true;
            },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, V2LocalMessageRepository::ReactionKind) {
            return true;
        },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [&](const QString &, const QString &, const QString &,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
                editedMentions = mentions;
                return true;
            },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &) { return true; });
    int participantRequests = 0;
    V2WindowsConversationParticipantViewModel participants(
        [&](const QString &requestedConversation, bool continuation) {
            ++participantRequests;
            return requestedConversation == conversation && !continuation;
        });
    const QString targetConversation =
        QStringLiteral("20000000-0000-4000-8000-000000000099");
    QString forwardedSource;
    QString forwardedMessage;
    QString forwardedTarget;
    model.configureForwarding(
        [&](const QString &source, const QString &sourceMessage,
            const QString &target, V2LocalMessageRepository::Message *) {
            forwardedSource = source;
            forwardedMessage = sourceMessage;
            forwardedTarget = target;
            return true;
        });
    V2WindowsConversationDirectoryViewModel directory(
        [] { return true; }, [] { return true; }, [](const QString &) { return true; });
    directory.applyPage({
        {conversation, QStringLiteral("原会话"), QStringLiteral("群聊"),
         QStringLiteral("成员"), 0},
        {targetConversation, QStringLiteral("目标会话"), QStringLiteral("群聊"),
         QStringLiteral("成员"), 0}}, false, false);
    model.openConversation(conversation);
    V2WindowsMessagingPanel defaultOff(&model, &participants);
    if (!defaultOff.mentionForTest()->isHidden()) {
        qCritical() << "mention control must be default-off";
        return 1;
    }
    const auto defaultButtons = defaultOff.findChildren<QPushButton *>(
        QString(), Qt::FindChildrenRecursively);
    if (std::any_of(defaultButtons.cbegin(), defaultButtons.cend(),
            [](QPushButton *button) {
                return button->accessibleName() == QStringLiteral("转发此消息");
            })) {
        qCritical() << "forward action must be default-off";
        return 1;
    }
    V2WindowsMessagingPanel panel(
        &model, &participants, nullptr, true, &directory, true);
    panel.setConversation(conversation);
    panel.show();
    if (panel.accessibleName().isEmpty()
            || panel.messageListForTest()->accessibleName().isEmpty()
            || panel.composerForTest()->accessibleName().isEmpty()
            || panel.sendForTest()->accessibleName().isEmpty()) {
        qCritical() << "core messaging controls lack accessible names";
        return 1;
    }
    const auto messageLabels = panel.findChildren<QLabel *>();
    const auto mentionedBody = std::find_if(
        messageLabels.cbegin(), messageLabels.cend(), [&](QLabel *label) {
            return label->property("mentionTargetAccountIds").toStringList()
                == QStringList{mentionTarget};
        });
    if (mentionedBody == messageLabels.cend()
            || !(*mentionedBody)->text().contains(QStringLiteral("<span"))
            || !(*mentionedBody)->text().contains(QStringLiteral("&lt;b&gt;"))
            || (*mentionedBody)->accessibleName()
                != QStringLiteral("消息内容：@张三😀 hello <b>x</b>")) {
        qCritical() << "identity-backed rich rendering failed";
        return 1;
    }
    const auto forwardedLabel = std::find_if(
        messageLabels.cbegin(), messageLabels.cend(), [](QLabel *label) {
            return label->accessibleName() == QStringLiteral("此消息由服务器转发");
        });
    if (forwardedLabel == messageLabels.cend()
            || (*forwardedLabel)->text() != QStringLiteral("已转发")) {
        qCritical() << "forwarded presentation marker missing";
        return 1;
    }
    auto replies = panel.findChildren<QPushButton *>(QString(), Qt::FindChildrenRecursively);
    auto reply = std::find_if(replies.cbegin(), replies.cend(), [](QPushButton *button) {
        return button->text() == QStringLiteral("回复");
    });
    if (reply == replies.cend()) {
        qCritical() << "reply action missing";
        return 1;
    }
    const auto forward = std::find_if(
        replies.cbegin(), replies.cend(), [](QPushButton *button) {
            return button->accessibleName() == QStringLiteral("转发此消息");
        });
    if (forward == replies.cend()) {
        qCritical() << "forward action missing from enabled panel";
        return 1;
    }
    QTimer::singleShot(0, [&] {
        auto *targetDialog = qobject_cast<V2WindowsForwardTargetDialog *>(
            QApplication::activeModalWidget());
        if (!targetDialog || targetDialog->targetListForTest()->count() != 1) return;
        targetDialog->targetListForTest()->setCurrentRow(0);
        targetDialog->forwardForTest()->click();
    });
    (*forward)->click();
    if (forwardedSource != conversation || forwardedMessage != message.messageId
            || forwardedTarget != targetConversation) {
        qCritical() << "forward picker did not preserve exact message identities";
        return 1;
    }
    const auto reactionButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isCheckable()
                && button->accessibleName().startsWith(QStringLiteral("消息反应"));
        });
    if (reactionButtons != 6) {
        qCritical() << "reaction actions missing";
        return 1;
    }
    const auto pinButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isCheckable()
                && button->accessibleName() == QStringLiteral("置顶此消息");
        });
    if (pinButtons != 1) {
        qCritical() << "pin action missing";
        return 1;
    }
    const auto editButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) { return button->accessibleName() == QStringLiteral("编辑此消息"); });
    if (editButtons != 1) {
        qCritical() << "initial edit action missing";
        return 1;
    }
    (*reply)->click();
    app.processEvents();
    if (!panel.cancelReplyForTest()->isVisible()
            || model.replyTargetMessageId() != message.messageId) {
        qCritical() << "reply composition did not activate";
        return 1;
    }
    panel.composerForTest()->setPlainText(QStringLiteral("reply text"));
    app.processEvents();
    if (!panel.sendForTest()->isEnabled()) {
        qCritical() << "non-empty reply was not sendable";
        return 1;
    }
    panel.cancelReplyForTest()->click();
    app.processEvents();
    if (!model.replyTargetMessageId().isEmpty()) {
        qCritical() << "reply cancellation did not clear target";
        return 1;
    }
    const auto refreshedButtons = panel.findChildren<QPushButton *>(
        QString(), Qt::FindChildrenRecursively);
    const auto secondReply = std::find_if(
        refreshedButtons.cbegin(), refreshedButtons.cend(), [](QPushButton *button) {
            return button->text() == QStringLiteral("回复");
        });
    if (secondReply == refreshedButtons.cend()) {
        qCritical() << "reply action was not rebuilt after cancellation";
        return 1;
    }
    (*secondReply)->click();
    panel.composerForTest()->clear();
    panel.mentionForTest()->click();
    app.processEvents();
    if (participantRequests != 1 || !participants.busy()) {
        qCritical() << "participant picker did not request active conversation";
        return 1;
    }
    participants.applyPage(conversation, {{
        mentionTarget, QStringLiteral("张三😀"), QStringLiteral("成员")}}, false, false);
    app.processEvents();
    if (panel.participantListForTest()->count() != 1
            || panel.participantListForTest()->accessibleName().isEmpty()) {
        qCritical() << "participant result was not accessible";
        return 1;
    }
    panel.participantListForTest()->itemActivated(
        panel.participantListForTest()->item(0));
    app.processEvents();
    if (panel.composerForTest()->toPlainText() != QStringLiteral("@张三😀 ")) {
        qCritical() << "participant selection did not compose Unicode mention";
        return 1;
    }
    panel.sendForTest()->click();
    app.processEvents();
    if (submittedMentions.size() != 1
            || submittedMentions.first().targetAccountId != mentionTarget
            || submittedMentions.first().startUtf8Byte != 0
            || submittedMentions.first().lengthUtf8Bytes
                != QStringLiteral("@张三😀").toUtf8().size()) {
        qCritical() << "reply did not submit exact mention span";
        return 1;
    }
    const auto currentButtons = panel.findChildren<QPushButton *>(
        QString(), Qt::FindChildrenRecursively);
    const auto edit = std::find_if(
        currentButtons.cbegin(), currentButtons.cend(), [](QPushButton *button) {
            return button->accessibleName() == QStringLiteral("编辑此消息");
        });
    if (edit == currentButtons.cend()) {
        qCritical() << "edit action missing after reply refresh";
        return 1;
    }
    (*edit)->click();
    app.processEvents();
    if (panel.composerForTest()->toPlainText() != message.text
            || panel.sendForTest()->accessibleName()
                != QStringLiteral("保存当前消息编辑")) {
        qCritical() << "edit composer did not restore text and accessible action";
        return 1;
    }
    panel.composerForTest()->moveCursor(QTextCursor::End);
    panel.composerForTest()->insertPlainText(QStringLiteral("!"));
    panel.sendForTest()->click();
    app.processEvents();
    if (editedMentions.size() != 1
            || editedMentions.first().targetAccountId != mentionTarget
            || editedMentions.first().startUtf8Byte != 0
            || editedMentions.first().lengthUtf8Bytes
                != message.mentions.first().lengthUtf8Bytes) {
        qCritical() << "edit did not preserve stored mention identity";
        return 1;
    }
    qInfo() << "[V2WindowsMessagingPanelTest] PASS";
    return 0;
}
