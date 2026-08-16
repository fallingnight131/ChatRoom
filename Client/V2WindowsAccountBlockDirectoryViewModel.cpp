#include "V2WindowsAccountBlockDirectoryViewModel.h"

#include <QSet>
#include <stdexcept>
#include <utility>

V2WindowsAccountBlockDirectoryViewModel::V2WindowsAccountBlockDirectoryViewModel(
        List list, QObject *parent)
    : QObject(parent), m_list(std::move(list)) {
    if (!m_list) throw std::invalid_argument("invalid account block directory action");
}

void V2WindowsAccountBlockDirectoryViewModel::bindSession(bool clearRows) {
    if (clearRows) m_rows.clear();
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
    emit changed();
}

bool V2WindowsAccountBlockDirectoryViewModel::refresh() {
    if (!m_available || m_busy) return false;
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
    if (!m_available || m_busy || !m_hasMore
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

void V2WindowsAccountBlockDirectoryViewModel::applyPage(
        QVector<Row> rows, const QString &nextAfterTargetAccountId, bool hasMore) {
    if (!m_busy) throw std::runtime_error("unsolicited account block directory page");
    if (!m_appendPending) m_rows.clear();
    QSet<QString> identities;
    for (const auto &row : std::as_const(m_rows)) identities.insert(row.targetAccountId);
    for (auto &row : rows) {
        if (!identities.contains(row.targetAccountId)) {
            identities.insert(row.targetAccountId);
            m_rows.append(std::move(row));
        }
    }
    m_nextAfterTargetAccountId = nextAfterTargetAccountId;
    m_hasMore = hasMore;
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
    emit changed();
}
