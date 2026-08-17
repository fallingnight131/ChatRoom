#include "UserInfoDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDialog>
#include <QDir>
#include <QLabel>
#include <QMetaObject>
#include <QPixmap>
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
    QPixmap avatar(32, 32);
    avatar.fill(Qt::blue);
    UserInfoDialog dialog(
        QStringLiteral("alice_id"), QStringLiteral("Alice 艾丽丝"), avatar,
        UserInfoDialog::Role::Administrator, nullptr, &viewModel);

    if (!check(dialog.windowTitle() == QStringLiteral("用户信息")
                   && dialog.roleForTest()->text().contains(
                       QStringLiteral("管理员"))
                   && dialog.nicknameForTest()->text().contains(
                       QStringLiteral("Alice 艾丽丝"))
                   && !dialog.avatarForTest()->accessibleName().isEmpty(),
               QStringLiteral("user info must compose Chinese typed role state")))
        return 1;

    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    if (!check(dialog.windowTitle() == QStringLiteral("User information")
                   && dialog.roleForTest()->text()
                       == QStringLiteral("Role: Administrator")
                   && dialog.nicknameForTest()->text()
                       == QStringLiteral("Nickname: Alice 艾丽丝")
                   && dialog.idForTest()->text()
                       == QStringLiteral("ID: alice_id")
                   && dialog.closeForTest()->text() == QStringLiteral("Close")
                   && dialog.avatarForTest()->accessibleName().contains(
                       QStringLiteral("Alice 艾丽丝")),
               QStringLiteral("locale switch must preserve identity and localize role")))
        return 1;

    if (!QMetaObject::invokeMethod(&dialog, "viewLargeAvatar",
                                   Qt::DirectConnection))
        return 1;
    const auto previews = dialog.findChildren<QDialog *>(
        QString(), Qt::FindDirectChildrenOnly);
    if (!check(previews.size() == 1
                   && previews.first()->windowTitle()
                       == QStringLiteral("Full-size avatar"),
               QStringLiteral("nested avatar preview must use active locale")))
        return 1;

    if (!viewModel.select(WindowsLocale::ZhCn)) return 1;
    application.processEvents();
    if (!check(previews.first()->windowTitle() == QStringLiteral("头像大图")
                   && dialog.roleForTest()->text().contains(
                       QStringLiteral("管理员")),
               QStringLiteral("open nested preview must recompose with parent")))
        return 1;

    qInfo() << "[WindowsUserInfoLocalizationTest] PASS";
    return 0;
}
