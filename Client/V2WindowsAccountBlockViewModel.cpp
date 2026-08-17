#include "V2WindowsAccountBlockViewModel.h"

#include <QUuid>
#include <stdexcept>
#include <utility>

V2WindowsAccountBlockViewModel::V2WindowsAccountBlockViewModel(
        Submit submit, QObject *parent, WindowsLocale locale)
    : QObject(parent), m_submit(std::move(submit)), m_locale(locale) {
    if (!m_submit) throw std::invalid_argument("invalid account block submit action");
}

bool V2WindowsAccountBlockViewModel::canSubmit() const {
    return !m_actorAccountId.isEmpty() && !m_targetAccountId.isEmpty()
        && m_state != State::Pending;
}

void V2WindowsAccountBlockViewModel::bindSession(const QString &actorAccountId) {
    if (actorAccountId.isEmpty()) throw std::invalid_argument("invalid block actor");
    if (m_actorAccountId == actorAccountId) {
        if (m_state == State::Unavailable) {
            m_state = m_targetAccountId.isEmpty() ? State::Unavailable : State::Unknown;
            const auto &copy = WindowsLocaleCatalog::messages(m_locale);
            m_statusText = m_targetAccountId.isEmpty()
                ? copy.accountBlockOpenDirect : copy.accountBlockStateUnknown;
            emit changed();
        }
        return;
    }
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    m_actorAccountId = actorAccountId;
    resetTarget(WindowsLocaleCatalog::messages(m_locale).accountBlockOpenDirect);
}

void V2WindowsAccountBlockViewModel::clearSession() {
    m_actorAccountId.clear();
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    resetTarget(WindowsLocaleCatalog::messages(m_locale).accountBlockServiceDisconnected);
}

bool V2WindowsAccountBlockViewModel::activateDirectConversation(
        const QString &conversationId, const QString &participantConversationId,
        const QVector<V2WindowsConversationParticipantViewModel::Row> &participants,
        bool hasMore, bool direct) {
    if (m_actorAccountId.isEmpty() || conversationId.isEmpty()
            || participantConversationId != conversationId || hasMore || !direct
            || participants.size() != 1) {
        resetTarget(WindowsLocaleCatalog::messages(m_locale).accountBlockDirectOnly);
        return false;
    }
    const auto &participant = participants.first();
    const QString targetAccountId = participant.accountId;
    const QString targetDisplayName = participant.displayName;
    if (targetAccountId.isEmpty() || targetAccountId == m_actorAccountId) {
        resetTarget(WindowsLocaleCatalog::messages(m_locale).accountBlockParticipantInvalid);
        return false;
    }
    if (m_conversationId != conversationId || m_targetAccountId != targetAccountId) {
        m_hasKnownState = false;
        m_clientOperationId.clear();
        m_operationTargetAccountId.clear();
    }
    m_conversationId = conversationId;
    m_targetAccountId = targetAccountId;
    m_targetDisplayName = targetDisplayName.isEmpty()
        ? WindowsLocaleCatalog::messages(m_locale).accountFallback : targetDisplayName;
    m_state = State::Unknown;
    m_statusText = WindowsLocaleCatalog::messages(m_locale).accountBlockStateUnknown;
    emit changed();
    return true;
}

bool V2WindowsAccountBlockViewModel::request(bool blocked) {
    if (!canSubmit()) return false;
    const bool stableRetry = m_state == State::Failed
        && !m_clientOperationId.isEmpty()
        && m_operationTargetAccountId == m_targetAccountId
        && m_operationBlocked == blocked;
    if (!stableRetry) m_clientOperationId = operationId();
    m_operationTargetAccountId = m_targetAccountId;
    m_operationBlocked = blocked;
    m_state = State::Pending;
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_statusText = blocked ? copy.accountBlocking : copy.accountUnblocking;
    emit changed();
    if (m_submit(m_targetAccountId, blocked, m_clientOperationId)) return true;
    m_state = State::Failed;
    m_statusText = copy.accountBlockNotSent;
    emit changed();
    return false;
}

void V2WindowsAccountBlockViewModel::applyResult(
        const QString &targetAccountId, bool blocked, bool,
        const QString &clientOperationId) {
    if (m_state != State::Pending || targetAccountId != m_operationTargetAccountId
            || blocked != m_operationBlocked || clientOperationId != m_clientOperationId)
        throw std::runtime_error("uncorrelated account block view result");
    m_hasKnownState = true;
    m_blocked = blocked;
    m_state = State::Applied;
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_statusText = blocked ? copy.accountBlocked : copy.accountUnblocked;
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    emit changed();
}

void V2WindowsAccountBlockViewModel::applyFailure(
        const QString &clientOperationId, bool retryable) {
    if (m_state != State::Pending || clientOperationId != m_clientOperationId)
        throw std::runtime_error("uncorrelated account block view failure");
    m_state = State::Failed;
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_statusText = retryable
        ? copy.accountBlockRetryableFailure : copy.accountBlockFailure;
    if (!retryable) {
        m_clientOperationId.clear();
        m_operationTargetAccountId.clear();
    }
    emit changed();
}

void V2WindowsAccountBlockViewModel::setUnavailable() {
    if (m_state == State::Pending) {
        m_state = State::Failed;
        m_statusText = WindowsLocaleCatalog::messages(m_locale)
            .accountBlockDisconnectedRetry;
    } else {
        m_state = State::Unavailable;
        m_statusText = WindowsLocaleCatalog::messages(m_locale)
            .accountBlockServiceReconnecting;
    }
    emit changed();
}

void V2WindowsAccountBlockViewModel::resetTarget(const QString &status) {
    m_conversationId.clear();
    m_targetAccountId.clear();
    m_targetDisplayName.clear();
    m_hasKnownState = false;
    m_blocked = false;
    m_state = State::Unavailable;
    m_statusText = status;
    emit changed();
}

QString V2WindowsAccountBlockViewModel::operationId() {
    return QUuid::createUuid().toString(QUuid::WithoutBraces).toLower();
}
