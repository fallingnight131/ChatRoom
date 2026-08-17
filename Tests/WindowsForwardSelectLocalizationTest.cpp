#include "ForwardSelectDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDialogButtonBox>
#include <QDir>
#include <QLineEdit>
#include <QListWidget>
#include <QPushButton>
#include <QSettings>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}
}

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    QSettings settings(
        QDir(temporary.path()).filePath(QStringLiteral("ui.ini")),
        QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel viewModel(&repository);
    const QList<ForwardSelectDialog::RoomTarget> rooms{
        {7, QStringLiteral("项目组"), 3}};
    const QList<ForwardSelectDialog::FriendTarget> friends{
        {QStringLiteral("alice"), QStringLiteral("Alice"), true, 2}};
    ForwardSelectDialog dialog(rooms, friends, nullptr, &viewModel);

    auto *friendItem = dialog.friendListForTest()->item(0);
    friendItem->setCheckState(Qt::Checked);
    if (!check(dialog.windowTitle() == QStringLiteral("转发到其他会话")
                   && friendItem->text().contains(QStringLiteral("在线"))
                   && friendItem->text().contains(QStringLiteral("未读:2"))
                   && dialog.selectedFriendUsernames().contains(
                       QStringLiteral("alice")),
               QStringLiteral("forward dialog must compose Chinese target state")))
        return 1;

    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    friendItem = dialog.friendListForTest()->item(0);
    if (!check(dialog.windowTitle()
                   == QStringLiteral("Forward to another conversation")
                   && dialog.friendTabForTest()->text() == QStringLiteral("Friends")
                   && friendItem->text().contains(QStringLiteral("Online"))
                   && friendItem->text().contains(QStringLiteral("Unread: 2"))
                   && friendItem->checkState() == Qt::Checked
                   && !dialog.friendListForTest()->accessibleName().isEmpty()
                   && dialog.buttonsForTest()->button(QDialogButtonBox::Ok)->text()
                       == QStringLiteral("Forward"),
               QStringLiteral("live locale switch must preserve selected identity")))
        return 1;

    dialog.roomTabForTest()->click();
    application.processEvents();
    if (!check(dialog.searchForTest()->placeholderText()
                   == QStringLiteral("Search room name or ID")
                   && dialog.roomListForTest()->item(0)->text().contains(
                       QStringLiteral("Unread: 3"))
                   && !dialog.roomListForTest()->accessibleName().isEmpty(),
               QStringLiteral("room destination surface must switch completely")))
        return 1;

    qInfo() << "[WindowsForwardSelectLocalizationTest] PASS";
    return 0;
}
