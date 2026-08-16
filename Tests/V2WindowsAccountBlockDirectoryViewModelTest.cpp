#include "V2WindowsAccountBlockDirectoryViewModel.h"

#include <QCoreApplication>
#include <QDebug>
#include <stdexcept>
#include <utility>

namespace {
bool check(bool value, const QString &message) {
    if (!value) qCritical().noquote() << message;
    return value;
}
template <typename F> bool throws(F action) {
    try { action(); return false; } catch (const std::exception &) { return true; }
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    const QString first = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString second = QStringLiteral("20000000-0000-4000-8000-000000000001");
    QString requestedAfter;
    bool send = true;
    V2WindowsAccountBlockDirectoryViewModel viewModel(
        [&](const QString &after) { requestedAfter = after; return send; });
    if (!check(!viewModel.refresh(), QStringLiteral("未认证目录不能发起请求"))) return 1;
    viewModel.bindSession(true);
    if (!check(viewModel.refresh() && viewModel.busy() && requestedAfter.isEmpty(),
               QStringLiteral("刷新没有从空游标开始"))
            || !check(throws([&] {
                    viewModel.applyFailure(QStringLiteral("重复响应"));
                    viewModel.applyFailure(QStringLiteral("重复响应"));
                }), QStringLiteral("重复目录响应必须失败关闭"))) return 1;
    if (!check(viewModel.refresh(), QStringLiteral("失败后应允许刷新"))) return 1;
    viewModel.applyPage({{first, QStringLiteral("甲"), 10}}, first, true);
    if (!check(viewModel.rows().size() == 1 && viewModel.hasMore()
                    && viewModel.loadMore() && requestedAfter == first,
               QStringLiteral("下一页没有使用服务端游标"))) return 1;
    viewModel.applyPage({{first, QStringLiteral("重复"), 11},
                         {second, QStringLiteral("乙"), 12}}, {}, false);
    if (!check(viewModel.rows().size() == 2 && !viewModel.hasMore()
                    && viewModel.rows().last().targetAccountId == second,
               QStringLiteral("追加页没有稳定去重或收口"))) return 1;
    send = false;
    if (!check(!viewModel.refresh() && !viewModel.busy()
                    && !viewModel.failure().isEmpty() && viewModel.rows().size() == 2,
               QStringLiteral("发送失败不应清除可见目录"))) return 1;
    viewModel.setUnavailable();
    if (!check(!viewModel.available() && viewModel.rows().size() == 2
                    && !viewModel.failure().isEmpty(),
               QStringLiteral("断线应保留页面内目录并禁用分页"))) return 1;
    viewModel.bindSession(false);
    if (!check(viewModel.available() && viewModel.rows().size() == 2,
               QStringLiteral("同账号重连不应清除页面内目录"))) return 1;
    viewModel.bindSession(true);
    if (!check(viewModel.available() && viewModel.rows().isEmpty(),
               QStringLiteral("新认证会话必须隔离旧账号目录"))) return 1;
    send = true;
    if (!check(viewModel.refresh(), QStringLiteral("上限测试刷新没有发起"))) return 1;
    QVector<V2WindowsAccountBlockDirectoryViewModel::Row> oversized;
    for (int index = 0; index < 501; ++index) {
        oversized.append({QStringLiteral("target-%1").arg(index),
                          QStringLiteral("用户 %1").arg(index), index + 1});
    }
    viewModel.applyPage(std::move(oversized), QStringLiteral("target-500"), true);
    if (!check(viewModel.rows().size()
                    == V2WindowsAccountBlockDirectoryViewModel::MaxRows
                    && !viewModel.hasMore(),
               QStringLiteral("页面内目录必须在 500 行停止继续分页"))) return 1;
    viewModel.clearSession();
    if (!check(!viewModel.available() && viewModel.rows().isEmpty(),
               QStringLiteral("退出必须清除敏感目录"))) return 1;
    qInfo() << "[V2WindowsAccountBlockDirectoryViewModelTest] PASS";
    return 0;
}
