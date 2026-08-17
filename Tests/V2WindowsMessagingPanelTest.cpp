#include "V2WindowsMessagingPanel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsForwardTargetDialog.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"

#include <QApplication>
#include <QClipboard>
#include <QEventLoop>
#include <QLabel>
#include <QKeyEvent>
#include <QListWidget>
#include <QLineEdit>
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
    snapshot.draft = QStringLiteral("restored draft");
    QList<V2LocalMessageRepository::Mention> submittedMentions;
    QList<V2LocalMessageRepository::Mention> editedMentions;
    QString submittedText;
    QString savedDraftConversation;
    QString savedDraft;
    int ordinarySubmitCount = 0;
    V2WindowsMessagingViewModel model(
        account, [&](const QString &) { return snapshot; },
        [&](const QString &, const QString &text,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
                ++ordinarySubmitCount;
                submittedText = text;
                submittedMentions = mentions;
                return true;
            },
        [&](const QString &, const QString &, const QString &,
            V2LocalMessageRepository::Message *,
            const QList<V2LocalMessageRepository::Mention> &mentions) {
                submittedMentions = mentions;
                return true;
            },
        [&](const QString &selectedConversation, const QString &draft) {
            savedDraftConversation = selectedConversation;
            savedDraft = draft;
            snapshot.draft = draft;
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
    if (defaultOff.searchInputForTest()->isVisible()) {
        qCritical() << "search surface must be default-off";
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
    QString searchConversation;
    QString searchQuery;
    QString contextMessageId;
    quint64 contextSequence = 0;
    V2WindowsMessageSearchViewModel search(
        [&](const QString &selected, const QString &query, quint64, bool) {
            searchConversation = selected;
            searchQuery = query;
            return true;
        },
        [&](const QString &selected, quint64 sequence, const QString &messageId) {
            searchConversation = selected;
            contextSequence = sequence;
            contextMessageId = messageId;
            return true;
        });
    {
        V2WindowsMessagingPanel english(
            &model, &participants, nullptr, true, &directory, true, &search,
            WindowsLocale::EnUs);
        english.setConversation(conversation);
        const auto buttons = english.findChildren<QPushButton *>(
            QString(), Qt::FindChildrenRecursively);
        const auto hasButton = [&](const QString &text) {
            return std::any_of(buttons.cbegin(), buttons.cend(),
                [&](QPushButton *button) { return button->text() == text; });
        };
        if (!hasButton(QStringLiteral("Search"))
                || !hasButton(QStringLiteral("Copy"))
                || !hasButton(QStringLiteral("Reply"))
                || !hasButton(QStringLiteral("Pin"))
                || english.searchInputForTest()->placeholderText()
                    != QStringLiteral("Enter 1 to 128 bytes of text")
                || english.participantListForTest()->accessibleName()
                    != QStringLiteral("Mentionable conversation members")) {
            qCritical() << "English search, participant, or timeline copy was not composed";
            return 1;
        }
    }
    V2WindowsMessagingPanel panel(
        &model, &participants, nullptr, true, &directory, true, &search);
    panel.setConversation(conversation);
    panel.show();
    if (panel.composerForTest()->toPlainText() != QStringLiteral("restored draft")) {
        qCritical() << "conversation draft was not restored";
        return 1;
    }
    panel.composerForTest()->setPlainText(QStringLiteral("debounced draft"));
    QEventLoop draftWait;
    QTimer::singleShot(450, &draftWait, &QEventLoop::quit);
    draftWait.exec();
    if (savedDraftConversation != conversation
            || savedDraft != QStringLiteral("debounced draft")) {
        qCritical() << "conversation draft was not saved after the quiet period";
        return 1;
    }
    if (panel.accessibleName().isEmpty()
            || panel.messageListForTest()->accessibleName().isEmpty()
            || panel.composerForTest()->accessibleName().isEmpty()
            || panel.composerBudgetForTest()->accessibleName().isEmpty()
            || panel.sendForTest()->accessibleName().isEmpty()
            || panel.searchInputForTest()->accessibleName().isEmpty()
            || panel.searchResultsForTest()->accessibleName().isEmpty()) {
        qCritical() << "core messaging controls lack accessible names";
        return 1;
    }
    panel.composerForTest()->setPlainText(QString(32768, QChar(0x00e9)));
    app.processEvents();
    if (!panel.sendForTest()->isEnabled()
            || !panel.composerBudgetForTest()->text().startsWith(
                QStringLiteral("65536 /"))) {
        qCritical() << "exact UTF-8 byte budget was not sendable";
        return 1;
    }
    panel.composerForTest()->setPlainText(QString(32769, QChar(0x00e9)));
    app.processEvents();
    if (panel.sendForTest()->isEnabled()
            || !panel.composerBudgetForTest()->text().contains(
                QStringLiteral("超过上限"))) {
        qCritical() << "UTF-8 byte budget did not block oversized Unicode text";
        return 1;
    }
    panel.composerForTest()->setPlainText(QStringLiteral("line one"));
    QKeyEvent newline(
        QEvent::KeyPress, Qt::Key_Return, Qt::ShiftModifier, QStringLiteral("\n"));
    QApplication::sendEvent(panel.composerForTest(), &newline);
    if (!panel.composerForTest()->toPlainText().contains(QLatin1Char('\n'))
            || ordinarySubmitCount != 0) {
        qCritical() << "modified Enter must remain a newline";
        return 1;
    }
    panel.composerForTest()->setPlainText(QStringLiteral("ordinary text"));
    app.processEvents();
    if (!panel.sendForTest()->isEnabled()
            || panel.sendForTest()->text() != QStringLiteral("发送消息")) {
        qCritical() << "ordinary conversation text was not sendable";
        return 1;
    }
    QKeyEvent send(
        QEvent::KeyPress, Qt::Key_Return, Qt::ControlModifier, QStringLiteral("\r"));
    QApplication::sendEvent(panel.composerForTest(), &send);
    app.processEvents();
    if (ordinarySubmitCount != 1
            || submittedText != QStringLiteral("ordinary text")
            || !savedDraft.isEmpty()
            || !panel.composerForTest()->toPlainText().isEmpty()) {
        qCritical() << "ordinary text composition did not send and clear";
        return 1;
    }
    panel.searchInputForTest()->setText(QStringLiteral("张三"));
    panel.searchButtonForTest()->click();
    app.processEvents();
    if (searchConversation != conversation || searchQuery != QStringLiteral("张三")) {
        qCritical() << "search form did not submit the active conversation";
        return 1;
    }
    search.applyPage(conversation, searchQuery, {{message.messageId, 1, account,
        message.text, 900, 0, 0}}, false, 1, false);
    app.processEvents();
    if (panel.searchResultsForTest()->count() != 1) {
        qCritical() << "validated transient search result was not rendered";
        return 1;
    }
    panel.searchResultsForTest()->setCurrentRow(0);
    emit panel.searchResultsForTest()->itemActivated(
        panel.searchResultsForTest()->currentItem());
    app.processEvents();
    if (!panel.messageListForTest()->currentItem()
            || panel.messageListForTest()->currentItem()->data(Qt::UserRole).toString()
                != message.messageId) {
        qCritical() << "keyboard search activation did not reveal a cached hit";
        return 1;
    }
    auto context = message;
    context.messageId = QStringLiteral("30000000-0000-4000-8000-000000000099");
    context.clientMessageId = QStringLiteral("context-client-id");
    context.conversationSequence = 99;
    context.text = QStringLiteral("未缓存搜索结果");
    search.applyPage(conversation, searchQuery,
        {{context.messageId, 99, account, context.text, 990, 0, 0}},
        false, 99, false);
    app.processEvents();
    panel.searchResultsForTest()->setCurrentRow(0);
    emit panel.searchResultsForTest()->itemActivated(
        panel.searchResultsForTest()->currentItem());
    app.processEvents();
    if (contextMessageId != context.messageId || contextSequence != 99) {
        qCritical() << "uncached search activation did not request bounded context";
        return 1;
    }
    model.applyTransientContext(conversation, {context});
    search.applyContextAvailable(context.messageId);
    app.processEvents();
    if (!panel.messageListForTest()->currentItem()
            || panel.messageListForTest()->currentItem()->data(Qt::UserRole).toString()
                != context.messageId) {
        qCritical() << "validated transient context did not reveal the uncached hit";
        return 1;
    }
    model.clearTransientContext();
    app.processEvents();
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
    const auto copy = std::find_if(replies.cbegin(), replies.cend(), [](QPushButton *button) {
        return button->isVisible()
            && button->accessibleName() == QStringLiteral("复制此消息正文");
    });
    if (copy == replies.cend()) {
        qCritical() << "accessible copy action missing";
        return 1;
    }
    (*copy)->click();
    app.processEvents();
    if (!QApplication::clipboard()
            || QApplication::clipboard()->text() != message.text) {
        qCritical() << "copy action did not preserve the plain message body";
        return 1;
    }
    auto reply = std::find_if(replies.cbegin(), replies.cend(), [](QPushButton *button) {
        return button->isVisible() && button->text() == QStringLiteral("回复");
    });
    if (reply == replies.cend()) {
        qCritical() << "reply action missing";
        return 1;
    }
    const auto forward = std::find_if(
        replies.cbegin(), replies.cend(), [](QPushButton *button) {
            return button->isVisible()
                && button->accessibleName() == QStringLiteral("转发此消息");
        });
    if (forward == replies.cend()) {
        qCritical() << "forward action missing from enabled panel";
        return 1;
    }
    const auto reactionButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isVisible() && button->isCheckable()
                && button->accessibleName().startsWith(QStringLiteral("消息反应"));
        });
    if (reactionButtons != 6) {
        qCritical() << "reaction actions missing";
        return 1;
    }
    const auto pinButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isVisible() && button->isCheckable()
                && button->accessibleName() == QStringLiteral("置顶此消息");
        });
    if (pinButtons != 1) {
        qCritical() << "pin action missing";
        return 1;
    }
    const auto editButtons = std::count_if(replies.cbegin(), replies.cend(),
        [](QPushButton *button) {
            return button->isVisible()
                && button->accessibleName() == QStringLiteral("编辑此消息");
        });
    if (editButtons != 1) {
        qCritical() << "initial edit action missing";
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
    QKeyEvent cancel(QEvent::KeyPress, Qt::Key_Escape, Qt::NoModifier);
    QApplication::sendEvent(panel.composerForTest(), &cancel);
    app.processEvents();
    if (!model.replyTargetMessageId().isEmpty()) {
        qCritical() << "reply cancellation did not clear target";
        return 1;
    }
    const auto refreshedButtons = panel.findChildren<QPushButton *>(
        QString(), Qt::FindChildrenRecursively);
    const auto secondReply = std::find_if(
        refreshedButtons.cbegin(), refreshedButtons.cend(), [](QPushButton *button) {
            return button->isVisible() && button->text() == QStringLiteral("回复");
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
            return button->isVisible()
                && button->accessibleName() == QStringLiteral("编辑此消息");
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
