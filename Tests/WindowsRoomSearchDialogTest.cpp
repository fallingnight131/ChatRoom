#include "RoomSearchDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QPushButton>
#include <QSettings>
#include <QTemporaryDir>
#include <QDebug>

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    QSettings settings(temporary.filePath(QStringLiteral("preferences.ini")),
                       QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel locale(&repository);
    RoomSearchDialog dialog(&locale);

    auto *input = dialog.findChild<QLineEdit *>(QStringLiteral("roomSearchInput"));
    auto *submit = dialog.findChild<QPushButton *>(QStringLiteral("roomSearchSubmit"));
    auto *results = dialog.findChild<QListWidget *>(QStringLiteral("roomSearchResults"));
    auto *status = dialog.findChild<QLabel *>(QStringLiteral("roomSearchStatus"));
    if (!input || !submit || !results || !status
            || dialog.windowTitle() != QStringLiteral("搜索聊天室")) {
        qCritical() << "initial room-search presentation is incomplete";
        return 1;
    }

    int avatarRequest = 0;
    int joinedRoom = 0;
    QString searchKeyword;
    QObject::connect(&dialog, &RoomSearchDialog::roomAvatarRequested,
                     [&](int roomId) { avatarRequest = roomId; });
    QObject::connect(&dialog, &RoomSearchDialog::joinRequested,
                     [&](int roomId) { joinedRoom = roomId; });
    QObject::connect(&dialog, &RoomSearchDialog::searchRequested,
                     [&](const QString &keyword) { searchKeyword = keyword; });

    input->setText(QStringLiteral("  研发 room  "));
    QPixmap avatar(36, 36);
    avatar.fill(Qt::blue);
    dialog.showResults({{42, QStringLiteral("产品 %2 🚀"), 2, false, true, avatar}});
    results->setCurrentRow(0);
    auto *join = dialog.findChild<QPushButton *>(QStringLiteral("roomSearchJoin_42"));
    if (!join || avatarRequest != 42 || results->currentItem()->data(Qt::UserRole).toInt() != 42) {
        qCritical() << "stable room-search row was not composed";
        return 1;
    }
    join->click();
    if (joinedRoom != 42 || join->isEnabled()) {
        qCritical() << "join intent was not single-flight";
        return 1;
    }

    if (!locale.select(WindowsLocale::EnUs)
            || input->text() != QStringLiteral("  研发 room  ")
            || results->currentItem()->data(Qt::UserRole).toInt() != 42
            || join->text() != QStringLiteral("Requested")
            || results->currentItem()->data(Qt::AccessibleTextRole).toString()
                != QStringLiteral("产品 %2 🚀, ID 42, 2 members")
            || dialog.windowTitle() != QStringLiteral("Find rooms")) {
        qCritical() << "live locale projection changed room-search state";
        return 1;
    }

    dialog.showFailure(QStringLiteral("opaque provider detail"));
    if (!locale.select(WindowsLocale::ZhCn)
            || status->text() != QStringLiteral("opaque provider detail")) {
        qCritical() << "opaque search failure did not survive locale projection";
        return 1;
    }
    input->setText(QStringLiteral("  alpha  "));
    submit->click();
    if (searchKeyword != QStringLiteral("alpha") || submit->isEnabled()) {
        qCritical() << "trimmed search intent or single-flight state failed";
        return 1;
    }
    QVector<RoomSearchDialog::Result> many;
    for (int index = 1; index <= 105; ++index) {
        RoomSearchDialog::Result result;
        result.roomId = index;
        result.roomName = QStringLiteral("room-%1").arg(index);
        result.avatar = avatar;
        many.push_back(result);
    }
    many.push_back(many.constFirst());
    dialog.showResults(many);
    if (results->count() != 100) {
        qCritical() << "room-search result bound was not enforced";
        return 1;
    }

    qInfo() << "[WindowsRoomSearchDialogTest] PASS";
    return 0;
}
