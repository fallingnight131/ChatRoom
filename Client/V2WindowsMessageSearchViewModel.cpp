#include "V2WindowsMessageSearchViewModel.h"

#include <QSet>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsMessageSearchViewModel::V2WindowsMessageSearchViewModel(
        Request request, ContextRequest contextRequest, QObject *parent,
        WindowsLocale locale)
    : QObject(parent), m_request(std::move(request)),
      m_contextRequest(std::move(contextRequest)), m_locale(locale) {
    if (!m_request) throw std::invalid_argument("invalid Windows search view model");
}

bool V2WindowsMessageSearchViewModel::activate(const QString &conversationId) {
    if (conversationId.isEmpty()) return false;
    if (conversationId == m_conversationId) return true;
    m_conversationId = conversationId;
    m_query.clear();
    m_rows.clear();
    m_nextBeforeSequence = 0;
    m_busy = false;
    m_contextBusy = false;
    m_contextMessageId.clear();
    m_hasMore = false;
    m_failure.clear();
    emit changed();
    return true;
}

bool V2WindowsMessageSearchViewModel::search(const QString &literalQuery) {
    const QString query = literalQuery.trimmed();
    if (m_conversationId.isEmpty() || m_busy || m_contextBusy || query.isEmpty()
            || query.toUtf8().size() > 128)
        return false;
    m_query = query;
    m_rows.clear();
    m_nextBeforeSequence = 0;
    m_hasMore = false;
    m_failure.clear();
    m_busy = true;
    emit changed();
    if (m_request(m_conversationId, m_query, 0, false)) return true;
    m_busy = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).searchMessagesFailed;
    emit changed();
    return false;
}

bool V2WindowsMessageSearchViewModel::loadMore() {
    if (m_conversationId.isEmpty() || m_query.isEmpty() || m_busy || m_contextBusy
            || !m_hasMore || m_rows.size() >= MaximumRows
            || m_nextBeforeSequence == 0)
        return false;
    m_busy = true;
    m_failure.clear();
    emit changed();
    if (m_request(m_conversationId, m_query, m_nextBeforeSequence, true)) return true;
    m_busy = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).loadMoreSearchFailed;
    emit changed();
    return false;
}

bool V2WindowsMessageSearchViewModel::requestContext(const QString &messageId) {
    if (!m_contextRequest || m_contextBusy || messageId.isEmpty()) return false;
    const auto hit = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.messageId == messageId; });
    if (hit == m_rows.cend() || hit->conversationSequence == 0) return false;
    m_contextBusy = true;
    m_contextMessageId = messageId;
    m_failure.clear();
    emit changed();
    if (m_contextRequest(m_conversationId, hit->conversationSequence, messageId))
        return true;
    m_contextBusy = false;
    m_contextMessageId.clear();
    m_failure = WindowsLocaleCatalog::messages(m_locale).loadMessageContextFailed;
    emit changed();
    return false;
}

void V2WindowsMessageSearchViewModel::applyContextAvailable(
        const QString &messageId) {
    if (!m_contextBusy || messageId != m_contextMessageId) return;
    m_contextBusy = false;
    m_contextMessageId.clear();
    m_failure.clear();
    emit changed();
}

void V2WindowsMessageSearchViewModel::applyContextFailure(
        const QString &messageId, const QString &safeReason) {
    if (!m_contextBusy || messageId != m_contextMessageId) return;
    m_contextBusy = false;
    m_contextMessageId.clear();
    m_failure = safeReason.isEmpty()
        ? WindowsLocaleCatalog::messages(m_locale).loadMessageContextFailed
        : safeReason;
    emit changed();
}

void V2WindowsMessageSearchViewModel::applyPage(
        const QString &conversationId, const QString &query,
        QVector<Row> rows, bool append,
        quint64 nextBeforeSequence, bool hasMore) {
    if (conversationId != m_conversationId || query != m_query) return;
    if (!append) m_rows.clear();
    QSet<QString> identities;
    for (const auto &row : std::as_const(m_rows)) identities.insert(row.messageId);
    for (auto &row : rows) {
        if (!identities.contains(row.messageId) && m_rows.size() < MaximumRows) {
            identities.insert(row.messageId);
            m_rows.append(std::move(row));
        }
    }
    m_nextBeforeSequence = nextBeforeSequence;
    m_busy = false;
    m_hasMore = hasMore && nextBeforeSequence > 0 && m_rows.size() < MaximumRows;
    m_failure.clear();
    emit changed();
}

void V2WindowsMessageSearchViewModel::applyFailure(
        const QString &conversationId, const QString &query,
        const QString &safeReason) {
    if (conversationId != m_conversationId || query != m_query) return;
    m_busy = false;
    m_failure = safeReason.isEmpty()
        ? WindowsLocaleCatalog::messages(m_locale).searchRequestFailed
        : safeReason;
    emit changed();
}

void V2WindowsMessageSearchViewModel::setUnavailable() {
    m_query.clear();
    m_rows.clear();
    m_nextBeforeSequence = 0;
    m_busy = false;
    m_contextBusy = false;
    m_contextMessageId.clear();
    m_hasMore = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).searchServiceUnavailable;
    emit changed();
}

void V2WindowsMessageSearchViewModel::setLocale(WindowsLocale locale) {
    if (m_locale == locale) return;
    m_locale = locale;
    m_failure.clear();
    emit changed();
}
