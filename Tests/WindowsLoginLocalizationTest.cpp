#include "LoginDialog.h"
#include "NetworkManager.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QComboBox>
#include <QDebug>
#include <QDir>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSettings>
#include <QTabWidget>
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

    const QString path = QDir(temporary.path()).filePath(QStringLiteral("ui.ini"));
    QSettings settings(path, QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel viewModel(&repository);
    LoginDialog dialog(nullptr, &viewModel);
    dialog.show();
    application.processEvents();

    if (!check(dialog.localeSelectorForTest()->currentIndex() == 0
                   && !dialog.localeSelectorForTest()
                       ->accessibleDescription().isEmpty(),
               QStringLiteral("login must expose the accepted locale accessibly")))
        return 1;

    dialog.loginButtonForTest()->click();
    if (!check(dialog.loginStatusForTest()->text()
                   == QStringLiteral("请输入用户ID和密码"),
               QStringLiteral("login validation must start in Chinese")))
        return 1;

    dialog.localeSelectorForTest()->setCurrentIndex(1);
    application.processEvents();
    if (!check(dialog.windowTitle() == QStringLiteral("Qt Chat Room - Sign in")
                   && dialog.tabsForTest()->tabText(0) == QStringLiteral("Sign in")
                   && dialog.tabsForTest()->tabText(1) == QStringLiteral("Register")
                   && dialog.loginButtonForTest()->text() == QStringLiteral("Sign in")
                   && dialog.loginStatusForTest()->text()
                       == QStringLiteral("Enter your user ID and password"),
               QStringLiteral("locale switch must recompose login and live validation")))
        return 1;

    dialog.tabsForTest()->setCurrentIndex(1);
    dialog.registerUserForTest()->setText(QStringLiteral("bad"));
    dialog.registerPasswordForTest()->setText(QStringLiteral("abcd"));
    dialog.registerConfirmPasswordForTest()->setText(QStringLiteral("abcd"));
    dialog.registerButtonForTest()->click();
    if (!check(dialog.registerStatusForTest()->text().contains(
                   QStringLiteral("6-20")),
               QStringLiteral("registration validation must use the active locale")))
        return 1;

    QSettings restartedSettings(path, QSettings::IniFormat);
    WindowsLocalePreferenceRepository restartedRepository(restartedSettings);
    WindowsLocaleViewModel restartedViewModel(&restartedRepository);
    LoginDialog restarted(nullptr, &restartedViewModel);
    if (!check(restarted.windowTitle() == QStringLiteral("Qt Chat Room - Sign in")
                   && restarted.localeSelectorForTest()->currentIndex() == 1,
               QStringLiteral("login must restore the persisted locale")))
        return 1;

    emit NetworkManager::instance()->connectionError(
        QStringLiteral("opaque-socket-code"));
    application.processEvents();
    if (!check(dialog.loginStatusForTest()->text().contains(
                   QStringLiteral("opaque-socket-code")),
               QStringLiteral("connection detail must remain opaque")))
        return 1;
    dialog.localeSelectorForTest()->setCurrentIndex(0);
    application.processEvents();
    if (!check(dialog.loginStatusForTest()->text().contains(
                   QStringLiteral("opaque-socket-code"))
                   && dialog.loginStatusForTest()->text().startsWith(
                       QStringLiteral("连接失败")),
               QStringLiteral("locale switch must retain opaque connection detail")))
        return 1;

    QSettings unwritable(temporary.path(), QSettings::IniFormat);
    WindowsLocalePreferenceRepository failingRepository(unwritable);
    WindowsLocaleViewModel failingViewModel(&failingRepository);
    LoginDialog failing(nullptr, &failingViewModel);
    failing.show();
    application.processEvents();
    failing.localeSelectorForTest()->setCurrentIndex(1);
    application.processEvents();
    if (!check(failing.localeSelectorForTest()->currentIndex() == 0
                   && failingViewModel.locale() == WindowsLocale::ZhCn
                   && !failing.localeStatusForTest()->text().isEmpty(),
               QStringLiteral("failed locale save must restore and announce old state")))
        return 1;

    qInfo() << "[WindowsLoginLocalizationTest] PASS";
    return 0;
}
