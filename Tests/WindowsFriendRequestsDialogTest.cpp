#include "FriendRequestsDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QLabel>
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
    FriendRequestsDialog dialog(&locale);

    auto *list = dialog.findChild<QListWidget *>(
        QStringLiteral("friendRequestsList"));
    auto *status = dialog.findChild<QLabel *>(
        QStringLiteral("friendRequestsStatus"));
    if (!list || !status || dialog.windowTitle() != QStringLiteral("好友申请")) {
        qCritical() << "initial friend-request presentation is incomplete";
        return 1;
    }

    int acceptedRequestId = 0;
    int rejectedRequestId = 0;
    QString acceptedUsername;
    QStringList avatarRequests;
    QObject::connect(&dialog, &FriendRequestsDialog::acceptRequested,
                     [&](int requestId, const QString &username) {
        acceptedRequestId = requestId;
        acceptedUsername = username;
    });
    QObject::connect(&dialog, &FriendRequestsDialog::rejectRequested,
                     [&](int requestId) { rejectedRequestId = requestId; });
    QObject::connect(&dialog, &FriendRequestsDialog::avatarRequested,
                     [&](const QString &username) {
        avatarRequests.push_back(username);
    });

    QPixmap avatar(36, 36);
    avatar.fill(Qt::blue);
    dialog.setRequests({
        {41, QStringLiteral("bob"), QStringLiteral("用户 %2"), true, avatar},
        {42, QStringLiteral("carol"), QString(), false, avatar},
        {42, QStringLiteral("duplicate-id"), QString(), false, avatar},
        {43, QStringLiteral("bob"), QString(), false, avatar},
        {0, QStringLiteral("invalid"), QString(), false, avatar},
    });
    list->setCurrentRow(0);
    auto *acceptBob = dialog.findChild<QPushButton *>(
        QStringLiteral("friendRequestAccept_41"));
    auto *rejectBob = dialog.findChild<QPushButton *>(
        QStringLiteral("friendRequestReject_41"));
    auto *acceptCarol = dialog.findChild<QPushButton *>(
        QStringLiteral("friendRequestAccept_42"));
    auto *rejectCarol = dialog.findChild<QPushButton *>(
        QStringLiteral("friendRequestReject_42"));
    if (list->count() != 2 || !acceptBob || !rejectBob
            || !acceptCarol || !rejectCarol
            || avatarRequests != QStringList{QStringLiteral("bob")}
            || list->currentItem()->data(Qt::UserRole).toInt() != 41) {
        qCritical() << "stable bounded friend-request rows were not composed";
        return 1;
    }

    acceptBob->click();
    rejectCarol->click();
    if (acceptedRequestId != 41 || acceptedUsername != QStringLiteral("bob")
            || rejectedRequestId != 0 || acceptBob->isEnabled()
            || rejectBob->isEnabled() || acceptCarol->isEnabled()
            || rejectCarol->isEnabled()) {
        qCritical() << "friend-request operation was not globally single-flight";
        return 1;
    }
    dialog.resolveReject(true, {});
    if (acceptBob->isEnabled()) {
        qCritical() << "wrong response type resolved the pending accept";
        return 1;
    }
    dialog.resolveAccept(false, QStringLiteral("opaque friend detail"));
    if (!acceptBob->isEnabled() || !rejectCarol->isEnabled()
            || status->text() != QStringLiteral("opaque friend detail")) {
        qCritical() << "failed accept did not restore actions and preserve detail";
        return 1;
    }

    acceptBob->click();
    dialog.resolveAccept(false, {});
    if (!locale.select(WindowsLocale::EnUs)
            || status->text()
                != QStringLiteral("Unable to process friend request")) {
        qCritical() << "empty failure did not use a live localized fallback";
        return 1;
    }
    if (!locale.select(WindowsLocale::ZhCn)
            || status->text() != QStringLiteral("好友申请处理失败")) {
        qCritical() << "failure fallback did not recompose live";
        return 1;
    }

    rejectCarol->click();
    dialog.resolveReject(true, {});
    acceptBob->click();
    dialog.resolveAccept(true, {});
    if (!locale.select(WindowsLocale::EnUs)
            || dialog.windowTitle() != QStringLiteral("Friend requests")
            || acceptBob->text() != QStringLiteral("Accepted")
            || rejectCarol->text() != QStringLiteral("Rejected")
            || list->currentItem()->data(Qt::UserRole).toInt() != 41
            || list->currentItem()->data(Qt::AccessibleTextRole).toString()
                != QStringLiteral("用户 %2, ID bob, friend request")) {
        qCritical() << "live locale projection changed request identity or state";
        return 1;
    }

    QVector<FriendRequestsDialog::Request> many;
    for (int index = 1; index <= 105; ++index) {
        FriendRequestsDialog::Request request;
        request.requestId = index;
        request.username = QStringLiteral("user-%1").arg(index);
        request.avatar = avatar;
        many.push_back(request);
    }
    dialog.setRequests(many);
    if (list->count() != 100) {
        qCritical() << "friend-request bound was not enforced";
        return 1;
    }

    dialog.setRequests({});
    if (!locale.select(WindowsLocale::ZhCn)
            || status->text() != QStringLiteral("暂无待处理的好友申请")) {
        qCritical() << "empty friend-request state was not localized";
        return 1;
    }

    qInfo() << "[WindowsFriendRequestsDialogTest] PASS";
    return 0;
}
