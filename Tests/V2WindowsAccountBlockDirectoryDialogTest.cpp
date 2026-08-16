#include "V2WindowsAccountBlockDirectoryDialog.h"

#include "V2WindowsAccountBlockDirectoryViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>

namespace {
bool check(bool value, const QString &message) {
    if (!value) qCritical().noquote() << message;
    return value;
}
}

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    const QString first = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString second = QStringLiteral("20000000-0000-4000-8000-000000000001");
    QString requestedAfter;
    QString unblockTarget;
    QString unblockOperation;
    int confirmations = 0;
    V2WindowsAccountBlockDirectoryViewModel viewModel(
        [&](const QString &after) { requestedAfter = after; return true; },
        [&](const QString &target, const QString &operation) {
            unblockTarget = target;
            unblockOperation = operation;
            return true;
        });
    V2WindowsAccountBlockDirectoryDialog dialog(
        &viewModel, [&](QWidget *, const QString &displayName) {
            ++confirmations;
            return displayName == QStringLiteral("甲");
        });
    dialog.show();
    application.processEvents();
    if (!check(!dialog.refreshForTest()->isEnabled()
                   && !dialog.accessibleName().isEmpty()
                   && !dialog.listForTest()->accessibleName().isEmpty(),
               QStringLiteral("未认证目录必须提供可访问的禁用状态"))) return 1;
    viewModel.bindSession(true);
    dialog.refreshForTest()->click();
    application.processEvents();
    if (!check(requestedAfter.isEmpty() && viewModel.busy()
                   && !dialog.refreshForTest()->isEnabled(),
               QStringLiteral("刷新动作没有进入单飞忙碌状态"))) return 1;
    viewModel.applyPage({{first, QStringLiteral("甲"), 100}}, first, true);
    application.processEvents();
    if (!check(dialog.listForTest()->count() == 1
                   && dialog.listForTest()->item(0)->data(Qt::UserRole) == first
                   && !dialog.listForTest()->item(0)
                           ->data(Qt::AccessibleTextRole).toString().isEmpty()
                   && dialog.loadMoreForTest()->isEnabled(),
               QStringLiteral("服务端目录没有形成可访问列表"))) return 1;
    dialog.loadMoreForTest()->click();
    application.processEvents();
    if (!check(requestedAfter == first && !dialog.loadMoreForTest()->isEnabled(),
               QStringLiteral("加载更多没有使用权威游标或阻止重复提交"))) return 1;
    viewModel.applyPage({{second, QStringLiteral("乙"), 200}}, {}, false);
    application.processEvents();
    if (!check(dialog.listForTest()->count() == 2
                   && !dialog.loadMoreForTest()->isEnabled()
                   && dialog.statusForTest()->text().contains(QStringLiteral("2")),
               QStringLiteral("终止页没有更新目录和状态"))) return 1;
    dialog.listForTest()->setCurrentRow(0);
    application.processEvents();
    if (!check(dialog.unblockForTest()->isEnabled()
                   && !dialog.unblockForTest()->accessibleName().isEmpty(),
               QStringLiteral("选择服务端行没有暴露可访问取消屏蔽动作"))) return 1;
    dialog.unblockForTest()->click();
    application.processEvents();
    if (!check(confirmations == 1 && unblockTarget == first
                   && !unblockOperation.isEmpty() && viewModel.mutationPending()
                   && !dialog.unblockForTest()->isEnabled(),
               QStringLiteral("确认取消屏蔽没有成为单一幂等操作"))) return 1;
    viewModel.applyUnblockResult(first, unblockOperation);
    application.processEvents();
    if (!check(dialog.listForTest()->count() == 1
                   && dialog.listForTest()->item(0)->data(Qt::UserRole) == second,
               QStringLiteral("权威取消屏蔽结果没有更新可见目录"))) return 1;
    viewModel.setUnavailable();
    application.processEvents();
    if (!check(dialog.listForTest()->count() == 1
                   && !dialog.refreshForTest()->isEnabled()
                   && dialog.statusForTest()->text().contains(QStringLiteral("断开")),
               QStringLiteral("断线界面应保留可见行并禁用网络动作"))) return 1;
    qInfo() << "[V2WindowsAccountBlockDirectoryDialogTest] PASS";
    return 0;
}
