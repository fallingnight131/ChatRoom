#include "RoomPasswordPromptDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDir>
#include <QLabel>
#include <QLineEdit>
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
    RoomPasswordPromptDialog dialog(73, nullptr, &viewModel);

    if (!check(dialog.windowTitle() == QStringLiteral("需要密码")
                   && dialog.passwordForTest()->echoMode() == QLineEdit::Password
                   && dialog.joinForTest()->text() == QStringLiteral("加入"),
               QStringLiteral("protected-room prompt must start secure in Chinese")))
        return 1;

    bool emitted = false;
    dialog.joinForTest()->click();
    if (!check(!emitted && dialog.statusForTest()->text()
                               == QStringLiteral("请输入聊天室密码"),
               QStringLiteral("empty password must be rejected locally")))
        return 1;

    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    if (!check(dialog.windowTitle() == QStringLiteral("Password required")
                   && dialog.promptForTest()->text().startsWith(
                       QStringLiteral("This room requires a password"))
                   && dialog.statusForTest()->text()
                       == QStringLiteral("Enter the room password")
                   && dialog.joinForTest()->text() == QStringLiteral("Join")
                   && dialog.cancelForTest()->text() == QStringLiteral("Cancel"),
               QStringLiteral("live switch must recompose prompt and validation")))
        return 1;

    bool clearBeforeEmit = false;
    int emittedRoomId = 0;
    QString emittedPassword;
    QObject::connect(&dialog, &RoomPasswordPromptDialog::joinRequested,
                     [&](int roomId, const QString &password) {
        emitted = true;
        emittedRoomId = roomId;
        emittedPassword = password;
        clearBeforeEmit = dialog.passwordForTest()->text().isEmpty();
    });
    dialog.passwordForTest()->setText(QStringLiteral(" secret Ω "));
    dialog.joinForTest()->click();
    if (!check(emitted && emittedRoomId == 73
                   && emittedPassword == QStringLiteral(" secret Ω ")
                   && clearBeforeEmit,
               QStringLiteral("submit must clear UI and preserve exact server room identity")))
        return 1;

    RoomPasswordPromptDialog cancelled(74, nullptr, &viewModel);
    cancelled.passwordForTest()->setText(QStringLiteral("discard-me"));
    cancelled.cancelForTest()->click();
    if (!check(cancelled.passwordForTest()->text().isEmpty(),
               QStringLiteral("cancel must clear component plaintext")))
        return 1;

    qInfo() << "[WindowsRoomPasswordPromptLocalizationTest] PASS";
    return 0;
}
