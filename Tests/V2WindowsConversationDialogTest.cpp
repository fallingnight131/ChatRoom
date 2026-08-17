#include "V2WindowsConversationDialog.h"

#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QComboBox>
#include <QDebug>
#include <QLabel>
#include <QListWidget>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QSettings>
#include <QTemporaryDir>
#include <algorithm>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}
}

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    const QString accountId = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString conversationId = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2LocalMessageRepository::Snapshot snapshot;
    V2LocalMessageRepository::Message message;
    message.conversationId = conversationId;
    message.messageId = QStringLiteral("30000000-0000-4000-8000-000000000001");
    message.senderAccountId = QStringLiteral("10000000-0000-4000-8000-000000000002");
    message.clientMessageId = QStringLiteral("remote-1");
    message.text = QStringLiteral("hello from V2");
    message.state = V2LocalMessageRepository::DeliveryState::Accepted;
    snapshot.messages.append(message);
    V2WindowsMessagingViewModel messaging(
        accountId, [&](const QString &) { return snapshot; },
        [](const QString &, const QString &,
           V2LocalMessageRepository::Message *,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &, const QString &,
           V2LocalMessageRepository::Message *,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &,
           V2LocalMessageRepository::ReactionKind) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &, const QString &,
           const QList<V2LocalMessageRepository::Mention> &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &, const QString &) { return true; },
        [](const QString &) { return true; });

    int refreshCalls = 0;
    int loadMoreCalls = 0;
    int openCalls = 0;
    V2WindowsConversationDirectoryViewModel directory(
        [&] { ++refreshCalls; return true; },
        [&] { ++loadMoreCalls; return true; },
        [&](const QString &selected) -> bool {
            ++openCalls;
            return messaging.openConversation(selected);
        });
    directory.applyPage({{
        conversationId, QStringLiteral("工程群"), QStringLiteral("群聊"),
        QStringLiteral("成员"), 3}}, false, true);
    int participantRequests = 0;
    V2WindowsConversationParticipantViewModel participants(
        [&](const QString &selected, bool continuation) {
            ++participantRequests;
            return selected == conversationId && !continuation;
        });

    {
        V2WindowsConversationDialog english(
            &directory, &messaging, &participants, nullptr, true, false,
            nullptr, nullptr, WindowsLocale::EnUs);
        if (!check(english.windowTitle()
                       == QStringLiteral("Conversations and replies (Preview)"),
                   QStringLiteral("English shell title must come from the catalog"))
                || !check(english.refreshForTest()->text() == QStringLiteral("Refresh"),
                          QStringLiteral("English directory action must be localized"))
                || !check(english.messagingPanelForTest()->composerForTest()
                              ->placeholderText() == QStringLiteral("Write a message"),
                          QStringLiteral("English composer placeholder must be localized"))
                || !check(english.messagingPanelForTest()->sendForTest()->text()
                              == QStringLiteral("Send message"),
                          QStringLiteral("English composer action must be localized"))
                || !check(english.messagingPanelForTest()->composerBudgetForTest()->text()
                              .endsWith(QStringLiteral("bytes")),
                          QStringLiteral("English byte budget must be localized"))) return 1;
    }

    {
        QTemporaryDir temporary;
        if (!check(temporary.isValid(),
                   QStringLiteral("locale preference directory must be available"))) return 1;
        const QString path = temporary.filePath(QStringLiteral("ui.ini"));
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel localeViewModel(&repository);
        V2WindowsConversationDialog localized(
            &directory, &messaging, &participants, nullptr, true, false,
            nullptr, nullptr, WindowsLocale::ZhCn, &localeViewModel);
        if (!check(!localized.localeSelectorForTest()->isHidden()
                       && localized.localeSelectorForTest()->currentIndex() == 0
                       && !localized.localeSelectorForTest()
                              ->accessibleDescription().isEmpty()
                       && localized.localeSelectorForTest()->nextInFocusChain()
                           == localized.refreshForTest(),
                   QStringLiteral("persisted locale selector must expose the current value")))
            return 1;
        localized.localeSelectorForTest()->setCurrentIndex(1);
        app.processEvents();
        localized.conversationListForTest()->setCurrentRow(0);
        app.processEvents();
        QSettings restarted(path, QSettings::IniFormat);
        const auto localizedButtons = localized.findChildren<QPushButton *>(
            QString(), Qt::FindChildrenRecursively);
        const auto hasLocalizedButton = [&](const QString &text) {
            return std::any_of(localizedButtons.cbegin(), localizedButtons.cend(),
                [&](QPushButton *button) { return button->text() == text; });
        };
        if (!check(localized.windowTitle()
                       == QStringLiteral("Conversations and replies (Preview)")
                       && localized.messagingPanelForTest()->sendForTest()->text()
                           == QStringLiteral("Send message")
                       && hasLocalizedButton(QStringLiteral("Copy"))
                       && hasLocalizedButton(QStringLiteral("Reply"))
                       && localized.messagingPanelForTest()->participantListForTest()
                              ->accessibleName()
                           == QStringLiteral("Mentionable conversation members")
                       && restarted.value(QStringLiteral("ui/locale")).toString()
                           == QStringLiteral("en-US"),
                   QStringLiteral("locale selection must persist and recompose")))
            return 1;
        openCalls = 0;
    }

    {
        QTemporaryDir temporary;
        QSettings settings(temporary.path(), QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel localeViewModel(&repository);
        V2WindowsConversationDialog failing(
            &directory, &messaging, &participants, nullptr, true, false,
            nullptr, nullptr, WindowsLocale::ZhCn, &localeViewModel);
        failing.show();
        app.processEvents();
        failing.localeSelectorForTest()->setCurrentIndex(1);
        app.processEvents();
        if (!check(failing.localeSelectorForTest()->currentIndex() == 0
                       && !failing.localeStatusForTest()->text().isEmpty()
                       && localeViewModel.locale() == WindowsLocale::ZhCn
                       && repository.load() == WindowsLocale::ZhCn,
                   QStringLiteral("failed locale save must announce and restore old value")))
            return 1;
    }

    V2WindowsConversationDialog dialog(
        &directory, &messaging, &participants, nullptr, true);
    dialog.show();
    app.processEvents();
    if (!check(!dialog.accessibleName().isEmpty(),
               QStringLiteral("dialog must have an accessible name"))
            || !check(!dialog.conversationListForTest()->accessibleName().isEmpty(),
                      QStringLiteral("directory must have an accessible name"))
            || !check(dialog.conversationListForTest()->count() == 1
                          && dialog.conversationListForTest()->item(0)->text().contains(
                              QStringLiteral("3 条未读")),
                      QStringLiteral("directory must render name and unread count"))
            || !check(dialog.loadMoreForTest()->isEnabled(),
                      QStringLiteral("validated continuation must enable load more"))) return 1;

    dialog.conversationListForTest()->setCurrentRow(0);
    app.processEvents();
    if (!check(openCalls == 1,
               QStringLiteral("selecting a row must open its hidden authorized identity"))
            || !check(dialog.messagingPanelForTest()->isEnabled()
                          && dialog.messagingPanelForTest()->messageListForTest()->count() == 1,
                      QStringLiteral("opened conversation must render cached messages"))) return 1;
    if (!check(participantRequests == 0,
               QStringLiteral("opening a conversation must not prefetch picker data"))) return 1;
    messaging.chooseReply(message.messageId);
    app.processEvents();
    dialog.messagingPanelForTest()->mentionForTest()->click();
    app.processEvents();
    if (!check(participantRequests == 1 && participants.busy(),
               QStringLiteral("opening the mention picker must explicitly request members")))
        return 1;

    dialog.loadMoreForTest()->click();
    app.processEvents();
    if (!check(loadMoreCalls == 1 && directory.busy(),
               QStringLiteral("load more must own one in-flight request"))) return 1;
    directory.applyPage({}, true, false);
    dialog.refreshForTest()->click();
    app.processEvents();
    if (!check(refreshCalls == 1 && directory.busy(),
               QStringLiteral("refresh must remain reachable after paging"))) return 1;

    qInfo() << "[V2WindowsConversationDialogTest] PASS";
    return 0;
}
