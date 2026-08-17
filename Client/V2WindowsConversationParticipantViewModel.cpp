#include "V2WindowsConversationParticipantViewModel.h"

#include <QSet>
#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsConversationParticipantViewModel::
V2WindowsConversationParticipantViewModel(
        Request request, QObject *parent, WindowsLocale locale)
    : QObject(parent), m_request(std::move(request)), m_locale(locale) {
    if (!m_request) throw std::invalid_argument("invalid participant view model");
}

bool V2WindowsConversationParticipantViewModel::activate(
        const QString &conversationId) {
    if (conversationId.isEmpty()) return false;
    if (conversationId != m_conversationId) {
        m_conversationId = conversationId;
        m_rows.clear();
        m_hasMore = false;
        m_failure.clear();
        m_busy = false;
    }
    return refresh();
}

bool V2WindowsConversationParticipantViewModel::refresh() {
    return request(false);
}

bool V2WindowsConversationParticipantViewModel::loadMore() {
    if (!m_hasMore || m_rows.size() >= MaximumRows) return false;
    return request(true);
}

bool V2WindowsConversationParticipantViewModel::request(bool continuation) {
    if (m_conversationId.isEmpty() || m_busy) return false;
    m_busy = true;
    m_failure.clear();
    emit changed();
    if (m_request(m_conversationId, continuation)) return true;
    m_busy = false;
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_failure = continuation ? copy.loadMoreParticipantsFailed
                             : copy.refreshParticipantsFailed;
    emit changed();
    return false;
}

void V2WindowsConversationParticipantViewModel::applyPage(
        const QString &conversationId, QVector<Row> rows,
        bool append, bool hasMore) {
    if (conversationId != m_conversationId) return;
    if (!append) m_rows.clear();
    QSet<QString> identities;
    for (const auto &row : std::as_const(m_rows)) identities.insert(row.accountId);
    for (auto &row : rows) {
        if (!identities.contains(row.accountId) && m_rows.size() < MaximumRows) {
            identities.insert(row.accountId);
            m_rows.append(std::move(row));
        }
    }
    std::sort(m_rows.begin(), m_rows.end(), [](const Row &left, const Row &right) {
        return left.accountId < right.accountId;
    });
    m_busy = false;
    m_hasMore = hasMore && m_rows.size() < MaximumRows;
    m_failure.clear();
    emit changed();
}

void V2WindowsConversationParticipantViewModel::applyFailure(
        const QString &conversationId, const QString &safeReason) {
    if (conversationId != m_conversationId) return;
    m_busy = false;
    m_failure = safeReason.isEmpty()
        ? WindowsLocaleCatalog::messages(m_locale).participantRequestFailed
        : safeReason;
    emit changed();
}

void V2WindowsConversationParticipantViewModel::setUnavailable() {
    m_busy = false;
    m_hasMore = false;
    m_failure = WindowsLocaleCatalog::messages(m_locale).participantServiceUnavailable;
    emit changed();
}

void V2WindowsConversationParticipantViewModel::setLocale(WindowsLocale locale) {
    if (m_locale == locale) return;
    m_locale = locale;
    m_failure.clear();
    emit changed();
}
