#include "V2WindowsAccountBlockDirectoryViewModel.h"

#include <QSet>
#include <QUuid>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsAccountBlockDirectoryViewModel::V2WindowsAccountBlockDirectoryViewModel(
        List list, Unblock unblock, QObject *parent)
    : QObject(parent), m_list(std::move(list)), m_unblock(std::move(unblock)) {
    if (!m_list || !m_unblock)
        throw std::invalid_argument("invalid account block directory action");
}

void V2WindowsAccountBlockDirectoryViewModel::bindSession(bool clearRows) {
    if (clearRows) m_rows.clear();
    if (clearRows) {
        m_mutationTargetAccountId.clear();
        m_mutationOperationId.clear();
        m_mutationFailure.clear();
        m_mutationPending = false;
    }
    m_nextAfterTargetAccountId.clear();
    m_failure.clear();
    m_available = true;
    m_busy = false;
    m_appendPending = false;
    m_hasMore = false;
    emit changed();
}

void V2WindowsAccountBlockDirectoryViewModel::clearSession() {
    m_rows.clear();
    m_nextAfterTargetAccountId.clear();
    m_failure = QStringLiteral("屏蔽目录已退出");
    m_available = false;
    m_busy = false;
    m_appendPending = false;
    m_hasMore = false;
    m_mutationTargetAccountId.clear();
    m_mutationOperationId.clear();
    m_mutationFailure.clear();
    m_mutationPending = false;
    emit changed();
}

bool V2WindowsAccountBlockDirectoryViewModel::refresh() {
    if (!m_available || m_busy || m_mutationPending) return false;
    m_busy = true;
    m_appendPending = false;
    m_failure.clear();
    emit changed();
    if (m_list({})) return true;
    m_busy = false;
    m_failure = QStringLiteral("无法刷新屏蔽目录");
    emit changed();
    return false;
}

bool V2WindowsAccountBlockDirectoryViewModel::loadMore() {
    if (!m_available || m_busy || m_mutationPending || !m_hasMore
            || m_nextAfterTargetAccountId.isEmpty()) return false;
    m_busy = true;
    m_appendPending = true;
    m_failure.clear();
    emit changed();
    if (m_list(m_nextAfterTargetAccountId)) return true;
    m_busy = false;
    m_appendPending = false;
    m_failure = QStringLiteral("无法加载更多屏蔽账号");
    emit changed();
    return false;
}

bool V2WindowsAccountBlockDirectoryViewModel::canUnblock(
        const QString &targetAccountId) const {
    return m_available && !m_busy && !m_mutationPending
        && std::any_of(m_rows.cbegin(), m_rows.cend(), [&](const Row &row) {
            return row.targetAccountId == targetAccountId;
        });
}

bool V2WindowsAccountBlockDirectoryViewModel::requestUnblock(
        const QString &targetAccountId) {
    if (!canUnblock(targetAccountId)) return false;
    const bool stableRetry = !m_mutationFailure.isEmpty()
        && m_mutationTargetAccountId == targetAccountId
        && !m_mutationOperationId.isEmpty();
    if (!stableRetry) {
        m_mutationOperationId = QUuid::createUuid()
            .toString(QUuid::WithoutBraces).toLower();
    }
    m_mutationTargetAccountId = targetAccountId;
    m_mutationFailure.clear();
    m_mutationPending = true;
    emit changed();
    if (m_unblock(targetAccountId, m_mutationOperationId)) return true;
    m_mutationPending = false;
    m_mutationFailure = QStringLiteral("取消屏蔽未发送，可重试");
    emit changed();
    return false;
}

bool V2WindowsAccountBlockDirectoryViewModel::ownsOperation(
        const QString &clientOperationId) const {
    return !clientOperationId.isEmpty()
        && clientOperationId == m_mutationOperationId;
}

void V2WindowsAccountBlockDirectoryViewModel::applyUnblockResult(
        const QString &targetAccountId, const QString &clientOperationId) {
    if (!m_mutationPending || targetAccountId != m_mutationTargetAccountId
            || clientOperationId != m_mutationOperationId)
        throw std::runtime_error("uncorrelated directory unblock result");
    m_rows.erase(std::remove_if(m_rows.begin(), m_rows.end(), [&](const Row &row) {
        return row.targetAccountId == targetAccountId;
    }), m_rows.end());
    m_mutationPending = false;
    m_mutationTargetAccountId.clear();
    m_mutationOperationId.clear();
    m_mutationFailure.clear();
    emit changed();
}

void V2WindowsAccountBlockDirectoryViewModel::applyUnblockFailure(
        const QString &clientOperationId, bool retryable) {
    if (!m_mutationPending || clientOperationId != m_mutationOperationId)
        throw std::runtime_error("uncorrelated directory unblock failure");
    m_mutationPending = false;
    m_mutationFailure = retryable
        ? QStringLiteral("取消屏蔽暂未完成，可重试")
        : QStringLiteral("无法取消屏蔽该账号");
    if (!retryable) {
        m_mutationTargetAccountId.clear();
        m_mutationOperationId.clear();
    }
    emit changed();
}

void V2WindowsAccountBlockDirectoryViewModel::applyPage(
        QVector<Row> rows, const QString &nextAfterTargetAccountId, bool hasMore) {
    if (!m_busy) throw std::runtime_error("unsolicited account block directory page");
    if (!m_appendPending) m_rows.clear();
    QSet<QString> identities;
    for (const auto &row : std::as_const(m_rows)) identities.insert(row.targetAccountId);
    for (auto &row : rows) {
        if (m_rows.size() >= MaxRows) break;
        if (!identities.contains(row.targetAccountId)) {
            identities.insert(row.targetAccountId);
            m_rows.append(std::move(row));
        }
    }
    m_nextAfterTargetAccountId = nextAfterTargetAccountId;
    m_hasMore = hasMore && m_rows.size() < MaxRows;
    m_busy = false;
    m_appendPending = false;
    m_failure.clear();
    emit changed();
}

void V2WindowsAccountBlockDirectoryViewModel::applyFailure(
        const QString &safeReason) {
    if (!m_busy) throw std::runtime_error("unsolicited account block directory failure");
    m_busy = false;
    m_appendPending = false;
    m_failure = safeReason.isEmpty()
        ? QStringLiteral("屏蔽目录请求失败") : safeReason;
    emit changed();
}

void V2WindowsAccountBlockDirectoryViewModel::setUnavailable() {
    m_available = false;
    m_busy = false;
    m_appendPending = false;
    m_hasMore = false;
    m_nextAfterTargetAccountId.clear();
    m_failure = QStringLiteral("屏蔽服务已断开，正在重连");
    if (m_mutationPending) {
        m_mutationPending = false;
        m_mutationFailure = QStringLiteral("连接已断开，可在重连后重试取消屏蔽");
    }
    emit changed();
}
