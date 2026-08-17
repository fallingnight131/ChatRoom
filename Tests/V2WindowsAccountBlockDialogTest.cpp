#include "V2WindowsAccountBlockDialog.h"

#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QLabel>
#include <QPushButton>

namespace {
bool check(bool value, const QString &message) {
    if (!value) qCritical().noquote() << message;
    return value;
}
}

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    const QString actor = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString target = QStringLiteral("20000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("30000000-0000-4000-8000-000000000001");
    QString operation;
    bool submittedBlocked = false;
    V2WindowsAccountBlockViewModel viewModel(
        [&](const QString &targetId, bool blocked, const QString &operationId) {
            if (targetId != target) return false;
            submittedBlocked = blocked;
            operation = operationId;
            return true;
        });
    viewModel.bindSession(actor);
    int participantRequests = 0;
    V2WindowsConversationParticipantViewModel participants(
        [&](const QString &id, bool continuation) {
            ++participantRequests;
            return id == conversation && !continuation;
        });
    int confirmations = 0;
    V2WindowsAccountBlockDialog dialog(
        &viewModel, &participants,
        [&](QWidget *, const QString &displayName, bool blocked) {
            ++confirmations;
            return displayName == QStringLiteral("对方") && blocked;
        });
    dialog.setConversation(conversation, true);
    dialog.show();
    application.processEvents();
    if (!check(participantRequests == 1 && participants.busy()
                   && !dialog.blockForTest()->isEnabled(),
               QStringLiteral("dialog must wait for authoritative participants"))) return 1;
    participants.applyPage(conversation,
        {{target, QStringLiteral("对方"), QStringLiteral("成员")}}, false, false);
    application.processEvents();
    if (!check(dialog.targetForTest()->text().contains(QStringLiteral("对方"))
                   && dialog.blockForTest()->isEnabled()
                   && !dialog.accessibleName().isEmpty()
                   && !dialog.blockForTest()->accessibleName().isEmpty()
                   && !dialog.statusForTest()->accessibleName().isEmpty(),
               QStringLiteral("validated direct target must expose accessible actions"))) return 1;
    dialog.blockForTest()->click();
    application.processEvents();
    if (!check(confirmations == 1 && submittedBlocked && !operation.isEmpty()
                   && !dialog.blockForTest()->isEnabled(),
               QStringLiteral("confirmed block must become one pending operation"))) return 1;
    viewModel.applyResult(target, true, true, operation);
    application.processEvents();
    if (!check(dialog.statusForTest()->text() == QStringLiteral("已屏蔽该账号")
                   && dialog.unblockForTest()->isEnabled(),
               QStringLiteral("correlated result must be announced and reversible"))) return 1;
    dialog.setConversation(conversation, false);
    application.processEvents();
    if (!check(!dialog.blockForTest()->isEnabled()
                   && dialog.statusForTest()->text().contains(QStringLiteral("仅可管理")),
               QStringLiteral("group context must remove account-block actions"))) return 1;

    V2WindowsAccountBlockViewModel englishViewModel(
        [](const QString &, bool, const QString &) { return true; },
        nullptr, WindowsLocale::EnUs);
    englishViewModel.bindSession(actor);
    V2WindowsConversationParticipantViewModel englishParticipants(
        [](const QString &, bool) { return true; }, nullptr, WindowsLocale::EnUs);
    englishParticipants.activate(conversation);
    englishParticipants.applyPage(conversation,
        {{target, QStringLiteral("Other person"), QStringLiteral("Member")}},
        false, false);
    V2WindowsAccountBlockDialog english(
        &englishViewModel, &englishParticipants,
        [](QWidget *, const QString &, bool) { return false; },
        nullptr, WindowsLocale::EnUs);
    english.setConversation(conversation, true);
    application.processEvents();
    if (!check(english.windowTitle()
                   == QStringLiteral("Direct-message account blocking")
                   && english.blockForTest()->text() == QStringLiteral("Block account")
                   && english.targetForTest()->text()
                       == QStringLiteral("Current account: Other person")
                   && english.statusForTest()->text()
                       == QStringLiteral("Current block state is unknown; submit the desired state"),
               QStringLiteral("English account-block dialog was not fully composed"))) return 1;
    qInfo() << "[V2WindowsAccountBlockDialogTest] PASS";
    return 0;
}
