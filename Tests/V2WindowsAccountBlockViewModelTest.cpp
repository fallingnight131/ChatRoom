#include "V2WindowsAccountBlockViewModel.h"

#include <QCoreApplication>
#include <QDebug>
#include <stdexcept>

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
    const QString actor = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString target = QStringLiteral("20000000-0000-4000-8000-000000000001");
    const QString conversation = QStringLiteral("30000000-0000-4000-8000-000000000001");
    QString submittedTarget;
    QString submittedOperation;
    bool submittedBlocked = false;
    V2WindowsAccountBlockViewModel viewModel(
        [&](const QString &targetId, bool blocked, const QString &operationId) {
            submittedTarget = targetId; submittedBlocked = blocked;
            submittedOperation = operationId; return true;
        });
    viewModel.bindSession(actor);
    const QVector<V2WindowsConversationParticipantViewModel::Row> direct{
        {target, QStringLiteral("测试用户"), QStringLiteral("成员")}};
    if (!check(!viewModel.activateDirectConversation(
                    conversation, conversation, direct, false, false),
               QStringLiteral("双人群组不能成为屏蔽目标来源"))
            || !check(!viewModel.activateDirectConversation(
                    conversation, conversation, direct, true, true),
               QStringLiteral("分页未完成的成员投影不能成为屏蔽目标"))
            || !check(viewModel.activateDirectConversation(
                    conversation, conversation, direct, false, true),
               QStringLiteral("权威双人私聊成员投影应解析唯一目标"))
            || !check(!viewModel.hasKnownState()
                    && viewModel.state() == V2WindowsAccountBlockViewModel::State::Unknown
                    && viewModel.targetAccountId() == target,
               QStringLiteral("新会话不能伪造已持久化屏蔽状态"))
            || !check(viewModel.request(true) && submittedTarget == target
                    && submittedBlocked && !submittedOperation.isEmpty(),
               QStringLiteral("屏蔽期望状态没有提交稳定操作标识"))) return 1;
    const QString firstOperation = submittedOperation;
    viewModel.setUnavailable();
    if (!check(viewModel.request(true) && submittedOperation == firstOperation,
               QStringLiteral("断线显式重试必须复用同一操作标识"))
            || !check(throws([&] {
                    viewModel.applyResult(actor, true, true, submittedOperation);
                }), QStringLiteral("目标替换响应必须失败关闭"))) return 1;
    viewModel.applyResult(target, true, true, submittedOperation);
    if (!check(viewModel.hasKnownState() && viewModel.blocked()
                    && viewModel.state() == V2WindowsAccountBlockViewModel::State::Applied,
               QStringLiteral("严格关联结果没有更新当前页状态"))) return 1;
    viewModel.clearSession();
    if (!check(!viewModel.canSubmit() && !viewModel.hasKnownState(),
               QStringLiteral("退出会话必须清除页面内屏蔽投影"))) return 1;
    qInfo() << "[V2WindowsAccountBlockViewModelTest] PASS";
    return 0;
}
