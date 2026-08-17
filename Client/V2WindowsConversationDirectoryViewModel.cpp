#include "V2WindowsConversationDirectoryViewModel.h"

#include <QSet>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsConversationDirectoryViewModel::V2WindowsConversationDirectoryViewModel(
        Action refresh, Action loadMore, OpenConversation open, QObject *parent,
        WindowsLocale locale)
    : QObject(parent), m_refresh(std::move(refresh)),
      m_loadMore(std::move(loadMore)), m_open(std::move(open)), m_locale(locale) {
    if (!m_refresh || !m_loadMore || !m_open)
        throw std::invalid_argument("invalid Windows V2 directory view model");
}

bool V2WindowsConversationDirectoryViewModel::refresh() {
    if (m_busy) return false;
    m_busy = true;
    m_failure.clear();
    emit changed();
    if (m_refresh()) return true;
    m_busy = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).refreshConversationsFailed;
    emit changed();
    return false;
}

bool V2WindowsConversationDirectoryViewModel::loadMore() {
    if (m_busy || !m_hasMore) return false;
    m_busy = true;
    m_failure.clear();
    emit changed();
    if (m_loadMore()) return true;
    m_busy = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).loadMoreConversationsFailed;
    emit changed();
    return false;
}

bool V2WindowsConversationDirectoryViewModel::openConversation(
        const QString &conversationId) {
    const auto exists = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.conversationId == conversationId; });
    if (exists == m_rows.cend() || !m_open(conversationId)) {
        m_failure = WindowsLocaleCatalog::messages(m_locale).openConversationFailed;
        emit changed();
        return false;
    }
    m_failure.clear();
    emit conversationOpened(conversationId);
    emit changed();
    return true;
}

void V2WindowsConversationDirectoryViewModel::applyPage(
        QVector<Row> rows, bool append, bool hasMore) {
    if (!append) m_rows.clear();
    QSet<QString> identities;
    for (const auto &row : std::as_const(m_rows)) identities.insert(row.conversationId);
    for (auto &row : rows) {
        if (!identities.contains(row.conversationId)) {
            identities.insert(row.conversationId);
            m_rows.append(std::move(row));
        }
    }
    m_busy = false;
    m_hasMore = hasMore;
    m_failure.clear();
    emit changed();
}

void V2WindowsConversationDirectoryViewModel::applyFailure(const QString &safeReason) {
    m_busy = false;
    m_failure = safeReason.isEmpty()
        ? WindowsLocaleCatalog::messages(m_locale).conversationRequestFailed
        : safeReason;
    emit changed();
}

void V2WindowsConversationDirectoryViewModel::setUnavailable() {
    m_busy = false;
    m_hasMore = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).conversationServiceUnavailable;
    emit changed();
}
