#include "FriendSearchDialog.h"
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
    FriendSearchDialog dialog(&locale);

    auto *input = dialog.findChild<QLineEdit *>(QStringLiteral("friendSearchInput"));
    auto *submit = dialog.findChild<QPushButton *>(QStringLiteral("friendSearchSubmit"));
    auto *results = dialog.findChild<QListWidget *>(QStringLiteral("friendSearchResults"));
    auto *status = dialog.findChild<QLabel *>(QStringLiteral("friendSearchStatus"));
    if (!input || !submit || !results || !status
            || dialog.windowTitle() != QStringLiteral("搜索好友")) {
        qCritical() << "initial friend-search presentation is incomplete";
        return 1;
    }

    QString avatarRequest;
    QString requestedAccount;
    QString searchKeyword;
    QObject::connect(&dialog, &FriendSearchDialog::avatarRequested,
                     [&](const QString &username) { avatarRequest = username; });
    QObject::connect(&dialog, &FriendSearchDialog::friendRequestRequested,
                     [&](const QString &username) { requestedAccount = username; });
    QObject::connect(&dialog, &FriendSearchDialog::searchRequested,
                     [&](const QString &keyword) { searchKeyword = keyword; });

    QPixmap avatar(36, 36);
    avatar.fill(Qt::blue);
    input->setText(QStringLiteral("  开发 friend  "));
    dialog.showResults({
        {QStringLiteral("self"), QStringLiteral("我"), true, false, true, false, avatar},
        {QStringLiteral("known"), QStringLiteral("已知"), false, true, false, false, avatar},
        {QStringLiteral("alice"), QStringLiteral("产品 %2 🚀"), true, false, false, true, avatar},
    });
    results->setCurrentRow(2);
    auto *self = dialog.findChild<QPushButton *>(QStringLiteral("friendSearchRequest_self"));
    auto *known = dialog.findChild<QPushButton *>(QStringLiteral("friendSearchRequest_known"));
    auto *request = dialog.findChild<QPushButton *>(QStringLiteral("friendSearchRequest_alice"));
    if (!self || !known || !request || self->isEnabled() || known->isEnabled()
            || avatarRequest != QStringLiteral("alice")
            || results->currentItem()->data(Qt::UserRole).toString()
                != QStringLiteral("alice")) {
        qCritical() << "stable friend-search rows were not composed";
        return 1;
    }
    request->click();
    if (requestedAccount != QStringLiteral("alice") || request->isEnabled()) {
        qCritical() << "friend request intent was not single-flight";
        return 1;
    }

    if (!locale.select(WindowsLocale::EnUs)
            || input->text() != QStringLiteral("  开发 friend  ")
            || results->currentItem()->data(Qt::UserRole).toString()
                != QStringLiteral("alice")
            || self->text() != QStringLiteral("Current account")
            || known->text() != QStringLiteral("Added")
            || request->text() != QStringLiteral("Sent")
            || results->currentItem()->data(Qt::AccessibleTextRole).toString()
                != QStringLiteral("产品 %2 🚀, ID alice, online")
            || dialog.windowTitle() != QStringLiteral("Find friends")) {
        qCritical() << "live locale projection changed friend-search state";
        return 1;
    }

    dialog.showFailure(QStringLiteral("opaque directory detail"));
    if (!locale.select(WindowsLocale::ZhCn)
            || status->text() != QStringLiteral("opaque directory detail")) {
        qCritical() << "opaque friend-search failure did not survive locale projection";
        return 1;
    }
    input->setText(QStringLiteral("  beta  "));
    submit->click();
    if (searchKeyword != QStringLiteral("beta") || submit->isEnabled()) {
        qCritical() << "trimmed friend search or single-flight state failed";
        return 1;
    }
    QVector<FriendSearchDialog::Result> many;
    for (int index = 0; index < 105; ++index) {
        FriendSearchDialog::Result result;
        result.username = QStringLiteral("user-%1").arg(index);
        result.avatar = avatar;
        many.push_back(result);
    }
    many.push_back(many.constFirst());
    dialog.showResults(many);
    if (results->count() != 100) {
        qCritical() << "friend-search result bound was not enforced";
        return 1;
    }

    qInfo() << "[WindowsFriendSearchDialogTest] PASS";
    return 0;
}
