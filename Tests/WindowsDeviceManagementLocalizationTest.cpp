#include "DeviceManagementDialog.h"
#include "DeviceManagementViewModel.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDir>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSettings>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}

DeviceManagementViewModel::Device device(
    const QString &id, bool current,
    DeviceManagementViewModel::Platform platform) {
    return {id, platform, 1700000000000LL, 1700000001000LL, current};
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
    WindowsLocaleViewModel locale(&repository);

    QString listRequest;
    QString revokedId;
    DeviceManagementViewModel model(
        [&] { listRequest = QStringLiteral("list-1"); return listRequest; },
        [&](const QString &id) { revokedId = id; return QStringLiteral("revoke-1"); });
    const QString current = QStringLiteral("30000000-0000-4000-8000-000000000001");
    const QString target = QStringLiteral("30000000-0000-4000-8000-000000000002");
    model.setAuthenticated(true, current);
    model.refresh();
    model.applyDirectory(listRequest,
        {device(current, true, DeviceManagementViewModel::Platform::Windows),
         device(target, false, DeviceManagementViewModel::Platform::Web)});
    DeviceManagementDialog dialog(&model, nullptr, &locale);
    auto *list = dialog.devicesForTest();
    list->setCurrentRow(1);
    dialog.show();
    application.processEvents();
    list->itemWidget(list->item(1))->findChild<QPushButton *>()->setFocus();
    if (!check(dialog.windowTitle() == QStringLiteral("登录设备")
                   && list->count() == 2
                   && list->item(1)->data(Qt::UserRole).toString() == target
                   && dialog.refreshForTest()->text() == QStringLiteral("刷新"),
               QStringLiteral("device dialog must compose Chinese stable identities")))
        return 1;

    if (!locale.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    QWidget *currentRow = list->itemWidget(list->item(0));
    QWidget *targetRow = list->itemWidget(list->item(1));
    const auto currentButtons = currentRow->findChildren<QPushButton *>();
    const auto targetButtons = targetRow->findChildren<QPushButton *>();
    const auto targetLabels = targetRow->findChildren<QLabel *>();
    if (!check(dialog.windowTitle() == QStringLiteral("Signed-in devices")
                   && list->currentItem()
                   && list->currentItem()->data(Qt::UserRole).toString() == target
                   && currentButtons.isEmpty() && targetButtons.size() == 1
                   && targetButtons.first()->text() == QStringLiteral("Revoke")
                   && targetButtons.first()->hasFocus()
                   && !targetLabels.isEmpty()
                   && targetLabels.first()->textFormat() == Qt::PlainText
                   && targetLabels.first()->text().contains(QStringLiteral("Web browser"))
                   && dialog.closeForTest()->text() == QStringLiteral("Close"),
               QStringLiteral("live switch must preserve selection and safe row identity")))
        return 1;

    if (!model.revoke(target) || revokedId != target) return 1;
    application.processEvents();
    targetRow = list->itemWidget(list->item(1));
    if (!check(targetRow->findChild<QPushButton *>()->text()
                   == QStringLiteral("Revoking…")
                   && !dialog.refreshForTest()->isEnabled(),
               QStringLiteral("locale rendering must preserve correlated revoke state")))
        return 1;

    DeviceManagementViewModel failed(
        [] { return QString(); }, [](const QString &) { return QString(); });
    failed.setAuthenticated(true, current);
    failed.refresh();
    DeviceManagementDialog failedDialog(&failed, nullptr, &locale);
    if (!check(failedDialog.statusForTest()->text()
                   == QStringLiteral("Unable to load signed-in devices"),
               QStringLiteral("typed failure must project through English catalog")))
        return 1;
    locale.select(WindowsLocale::ZhCn);
    application.processEvents();
    if (!check(failedDialog.statusForTest()->text()
                   == QStringLiteral("无法加载登录设备"),
               QStringLiteral("typed failure must recompose without replay")))
        return 1;

    qInfo() << "[WindowsDeviceManagementLocalizationTest] PASS";
    return 0;
}
