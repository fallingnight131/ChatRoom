#include "ProfileDialog.h"
#include "WindowsBandwidthPreferenceRepository.h"
#include "WindowsBandwidthViewModel.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QCheckBox>
#include <QComboBox>
#include <QDebug>
#include <QDir>
#include <QLabel>
#include <QSettings>
#include <QTemporaryDir>
#include <QPushButton>
#include <algorithm>

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
    WindowsBandwidthPreferenceRepository repository(settings);
    WindowsBandwidthViewModel viewModel(&repository);
    ProfileDialog dialog(1, QStringLiteral("alice"), QStringLiteral("Alice"),
                         {}, nullptr, &viewModel);
    dialog.show();
    application.processEvents();
    auto *control = dialog.lowBandwidthForTest();
    if (!check(control && !control->isChecked()
                   && !control->accessibleDescription().isEmpty(),
               QStringLiteral("profile must expose the accessible saved preference")))
        return 1;
    control->click();
    application.processEvents();
    QSettings restarted(path, QSettings::IniFormat);
    if (!check(control->isChecked() && viewModel.enabled()
                   && restarted.value(QStringLiteral("ui/lowBandwidth")).toString()
                       == QStringLiteral("true"),
               QStringLiteral("profile selection must persist through the ViewModel")))
        return 1;

    QSettings localeSettings(path, QSettings::IniFormat);
    WindowsLocalePreferenceRepository localeRepository(localeSettings);
    WindowsLocaleViewModel localeViewModel(&localeRepository);
    ProfileDialog localized(
        3, QStringLiteral("carol"), QStringLiteral("Carol"), {}, nullptr,
        &viewModel, &localeViewModel);
    localized.show();
    application.processEvents();
    if (!check(localized.localeSelectorForTest()
                   && localized.localeSelectorForTest()->currentIndex() == 0
                   && !localized.localeSelectorForTest()
                       ->accessibleDescription().isEmpty(),
               QStringLiteral("profile must expose the accepted locale"))) return 1;
    localized.localeSelectorForTest()->setCurrentIndex(1);
    application.processEvents();
    const auto buttons = localized.findChildren<QPushButton *>();
    const auto hasButton = [&](const QString &text) {
        return std::any_of(buttons.cbegin(), buttons.cend(),
            [&](QPushButton *button) { return button->text() == text; });
    };
    if (!check(localized.windowTitle() == QStringLiteral("Edit profile")
                   && localized.lowBandwidthForTest()->text()
                       == QStringLiteral("Low-bandwidth mode")
                   && localized.lowBandwidthForTest()->accessibleDescription()
                       .startsWith(QStringLiteral("Stops automatic requests"))
                   && hasButton(QStringLiteral("Change avatar"))
                   && hasButton(QStringLiteral("Change password"))
                   && hasButton(QStringLiteral("Close")),
               QStringLiteral("profile must recompose every visible action in English")))
        return 1;
    QSettings restartedLocaleSettings(path, QSettings::IniFormat);
    WindowsLocalePreferenceRepository restartedLocaleRepository(
        restartedLocaleSettings);
    WindowsLocaleViewModel restartedLocaleViewModel(&restartedLocaleRepository);
    ProfileDialog restartedLocaleDialog(
        4, QStringLiteral("dave"), QStringLiteral("Dave"), {}, nullptr,
        &viewModel, &restartedLocaleViewModel);
    if (!check(restartedLocaleDialog.windowTitle() == QStringLiteral("Edit profile"),
               QStringLiteral("profile must start from the persisted locale")))
        return 1;

    QSettings unwritable(temporary.path(), QSettings::IniFormat);
    WindowsBandwidthPreferenceRepository failingRepository(unwritable);
    WindowsBandwidthViewModel failingViewModel(&failingRepository);
    WindowsLocalePreferenceRepository failingLocaleRepository(unwritable);
    WindowsLocaleViewModel failingLocaleViewModel(&failingLocaleRepository);
    ProfileDialog failingDialog(
        2, QStringLiteral("bob"), QStringLiteral("Bob"), {}, nullptr,
        &failingViewModel, &failingLocaleViewModel);
    failingDialog.show();
    application.processEvents();
    failingDialog.lowBandwidthForTest()->click();
    application.processEvents();
    if (!check(!failingDialog.lowBandwidthForTest()->isChecked()
                   && !failingViewModel.enabled() && failingViewModel.saveFailed()
                   && failingDialog.bandwidthStatusForTest()
                   && !failingDialog.bandwidthStatusForTest()->text().isEmpty(),
               QStringLiteral("failed profile save must restore and report old state")))
        return 1;
    failingDialog.localeSelectorForTest()->setCurrentIndex(1);
    application.processEvents();
    if (!check(failingDialog.localeSelectorForTest()->currentIndex() == 0
                   && failingLocaleViewModel.locale() == WindowsLocale::ZhCn
                   && failingDialog.localeStatusForTest()
                   && !failingDialog.localeStatusForTest()->text().isEmpty(),
               QStringLiteral("failed profile locale save must restore old value")))
        return 1;

    qInfo() << "[WindowsProfileBandwidthTest] PASS";
    return 0;
}
