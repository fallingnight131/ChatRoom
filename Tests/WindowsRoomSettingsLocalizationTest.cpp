#include "RoomSettingsDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDir>
#include <QGroupBox>
#include <QLineEdit>
#include <QMetaObject>
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
    QApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    QSettings settings(
        QDir(temporary.path()).filePath(QStringLiteral("ui.ini")),
        QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel viewModel(&repository);
    RoomSettingsDialog dialog(
        42, QStringLiteral("项目组 Ω"), true,
        1024LL * 1024 * 1024, 10LL * 1024 * 1024 * 1024,
        1500, 50, nullptr, &viewModel);

    if (!check(dialog.windowTitle() == QStringLiteral("房间设置")
                   && dialog.currentLimitsForTest()->title()
                       == QStringLiteral("当前限制")
                   && dialog.administratorForTest()->title()
                       == QStringLiteral("管理员设置")
                   && dialog.passwordForTest()->echoMode() == QLineEdit::Password,
               QStringLiteral("room settings must compose secure Chinese UI")))
        return 1;

    dialog.nameForTest()->setText(QStringLiteral("未保存 Δ"));
    dialog.developerKeyForTest()->setText(QStringLiteral("secret-key"));
    dialog.passwordForTest()->setText(QStringLiteral("room-secret"));
    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    const auto buttons = dialog.findChildren<QPushButton *>();
    const auto hasButton = [&](const QString &text) {
        return std::any_of(buttons.cbegin(), buttons.cend(),
            [&](QPushButton *button) { return button->text() == text; });
    };
    if (!check(dialog.windowTitle() == QStringLiteral("Room settings")
                   && dialog.currentLimitsForTest()->title()
                       == QStringLiteral("Current limits")
                   && dialog.administratorForTest()->title()
                       == QStringLiteral("Administrator settings")
                   && dialog.nameForTest()->text() == QStringLiteral("未保存 Δ")
                   && dialog.developerKeyForTest()->text()
                       == QStringLiteral("secret-key")
                   && dialog.passwordForTest()->text()
                       == QStringLiteral("room-secret")
                   && dialog.passwordForTest()->placeholderText()
                       == QStringLiteral("Leave empty to remove the password")
                   && hasButton(QStringLiteral("Choose image"))
                   && hasButton(QStringLiteral("Save limits"))
                   && hasButton(QStringLiteral("Set"))
                   && hasButton(QStringLiteral("Check status"))
                   && hasButton(QStringLiteral("Leave room"))
                   && hasButton(QStringLiteral("Delete room"))
                   && hasButton(QStringLiteral("Close")),
               QStringLiteral("live switch must localize all groups without editing data")))
        return 1;

    bool limitsRequested = false;
    QObject::connect(&dialog, &RoomSettingsDialog::roomLimitsSaveRequested,
                     [&](int roomId) { limitsRequested = roomId == 42; });
    if (!QMetaObject::invokeMethod(&dialog, "onSaveLimits", Qt::DirectConnection)
            || !limitsRequested || !dialog.developerKeyForTest()->text().isEmpty()) {
        qCritical() << "successful limit submission must clear the developer key";
        return 1;
    }
    if (!QMetaObject::invokeMethod(&dialog, "onSetPassword", Qt::DirectConnection)
            || !dialog.passwordForTest()->text().isEmpty()) {
        qCritical() << "room password submission must clear the input";
        return 1;
    }

    qInfo() << "[WindowsRoomSettingsLocalizationTest] PASS";
    return 0;
}
