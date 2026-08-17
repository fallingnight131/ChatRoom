#include "ProfileDialog.h"
#include "WindowsBandwidthPreferenceRepository.h"
#include "WindowsBandwidthViewModel.h"

#include <QApplication>
#include <QCheckBox>
#include <QDebug>
#include <QDir>
#include <QLabel>
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

    QSettings unwritable(temporary.path(), QSettings::IniFormat);
    WindowsBandwidthPreferenceRepository failingRepository(unwritable);
    WindowsBandwidthViewModel failingViewModel(&failingRepository);
    ProfileDialog failingDialog(
        2, QStringLiteral("bob"), QStringLiteral("Bob"), {}, nullptr,
        &failingViewModel);
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

    qInfo() << "[WindowsProfileBandwidthTest] PASS";
    return 0;
}
