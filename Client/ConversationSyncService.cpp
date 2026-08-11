#include "ConversationSyncService.h"

ConversationSyncService::ConversationSyncService(
    LocalConversationRepository *repository, const QString &account)
    : m_repository(repository), m_account(account) {
}

void ConversationSyncService::setContext(
    LocalConversationRepository *repository, const QString &account,
    bool resetCursors) {
    m_repository = repository;
    m_account = account;
    if (resetCursors) m_cursors.clear();
    m_lastError.clear();
}

LocalConversationRepository::Snapshot ConversationSyncService::hydrate(
    const ConversationRef &conversation) {
    LocalConversationRepository::Snapshot snapshot;
    m_lastError.clear();
    if (!validate(conversation) || !m_repository) return snapshot;
    snapshot = m_repository->loadSnapshot(
        m_account, conversation.kind, conversation.key);
    if (!m_repository->lastError().isEmpty()) {
        m_lastError = m_repository->lastError();
        return snapshot;
    }
    advance(conversation, snapshot.cursor);
    return snapshot;
}

qint64 ConversationSyncService::cursor(
    const ConversationRef &conversation) const {
    return m_cursors.value(cursorKey(conversation), 0);
}

qint64 ConversationSyncService::advance(
    const ConversationRef &conversation, qint64 sequence) {
    if (conversation.key.isEmpty() || sequence <= 0)
        return cursor(conversation);
    const QString key = cursorKey(conversation);
    if (sequence > m_cursors.value(key, 0)) m_cursors[key] = sequence;
    return m_cursors.value(key, 0);
}

bool ConversationSyncService::replace(
    const ConversationRef &conversation, const QList<Message> &messages) {
    m_lastError.clear();
    if (!validate(conversation)) return false;
    if (!m_repository) return true;
    if (m_repository->replaceMessages(
            m_account, conversation.kind, conversation.key, messages,
            cursor(conversation))) return true;
    m_lastError = m_repository->lastError();
    return false;
}

bool ConversationSyncService::upsert(
    const ConversationRef &conversation, const Message &message) {
    m_lastError.clear();
    if (!validate(conversation)) return false;
    if (!m_repository) return true;
    if (m_repository->upsertMessage(
            m_account, conversation.kind, conversation.key, message,
            cursor(conversation))) return true;
    m_lastError = m_repository->lastError();
    return false;
}

bool ConversationSyncService::remove(
    const ConversationRef &conversation) {
    m_lastError.clear();
    if (!validate(conversation)) return false;
    forget(conversation);
    if (!m_repository) return true;
    if (m_repository->removeConversation(
            m_account, conversation.kind, conversation.key)) return true;
    m_lastError = m_repository->lastError();
    return false;
}

void ConversationSyncService::forget(const ConversationRef &conversation) {
    m_cursors.remove(cursorKey(conversation));
}

void ConversationSyncService::moveCursor(
    const ConversationRef &source, const ConversationRef &target) {
    const qint64 sourceCursor = m_cursors.take(cursorKey(source));
    advance(target, sourceCursor);
}

bool ConversationSyncService::clearCachedMessages() {
    m_lastError.clear();
    if (m_account.isEmpty()) {
        m_lastError = QStringLiteral("missing active account");
        return false;
    }
    if (m_repository && !m_repository->clearCachedMessages(m_account)) {
        m_lastError = m_repository->lastError();
        return false;
    }
    m_cursors.clear();
    return true;
}

QString ConversationSyncService::cursorKey(
    const ConversationRef &conversation) {
    return (conversation.kind == LocalConversationRepository::Kind::Room
                ? QStringLiteral("room:") : QStringLiteral("direct:"))
        + conversation.key;
}

bool ConversationSyncService::validate(
    const ConversationRef &conversation) {
    if (m_account.isEmpty() || conversation.key.isEmpty()) {
        m_lastError = QStringLiteral("missing account or conversation key");
        return false;
    }
    return true;
}
