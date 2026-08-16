#include "V2WindowsAccountBlockViewModel.h"

#include <QSet>
#include <QUuid>
#include <stdexcept>
#include <utility>

V2WindowsAccountBlockViewModel::V2WindowsAccountBlockViewModel(
        Submit submit, QObject *parent)
    : QObject(parent), m_submit(std::move(submit)) {
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
            m_statusText = m_targetAccountId.isEmpty()
                ? QStringLiteral("请先打开私聊并刷新成员信息")
                : QStringLiteral("当前屏蔽状态未知，可提交期望状态");
            emit changed();
        }
        return;
    }
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    m_actorAccountId = actorAccountId;
    resetTarget(QStringLiteral("请先打开私聊并刷新成员信息"));
}

void V2WindowsAccountBlockViewModel::clearSession() {
    m_actorAccountId.clear();
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    resetTarget(QStringLiteral("屏蔽服务已断开"));
}

bool V2WindowsAccountBlockViewModel::activateDirectConversation(
        const QString &conversationId, const QString &participantConversationId,
        const QVector<V2WindowsConversationParticipantViewModel::Row> &participants,
        bool hasMore, bool direct) {
    if (m_actorAccountId.isEmpty() || conversationId.isEmpty()
            || participantConversationId != conversationId || hasMore || !direct
            || participants.size() != 2) {
        resetTarget(QStringLiteral("仅可管理成员信息完整的私聊对象"));
        return false;
    }
    QSet<QString> identities;
    QString targetAccountId;
    QString targetDisplayName;
    bool actorFound = false;
    for (const auto &participant : participants) {
        if (participant.accountId.isEmpty()
                || identities.contains(participant.accountId)) {
            resetTarget(QStringLiteral("私聊成员信息无效，请刷新后重试"));
            return false;
        }
        identities.insert(participant.accountId);
        if (participant.accountId == m_actorAccountId) {
            actorFound = true;
        } else {
            targetAccountId = participant.accountId;
            targetDisplayName = participant.displayName;
        }
    }
    if (!actorFound || targetAccountId.isEmpty()) {
        resetTarget(QStringLiteral("私聊成员信息无效，请刷新后重试"));
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
        ? QStringLiteral("该账号") : targetDisplayName;
    m_state = State::Unknown;
    m_statusText = QStringLiteral("当前屏蔽状态未知，可提交期望状态");
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
    m_statusText = blocked ? QStringLiteral("正在屏蔽…")
                           : QStringLiteral("正在取消屏蔽…");
    emit changed();
    if (m_submit(m_targetAccountId, blocked, m_clientOperationId)) return true;
    m_state = State::Failed;
    m_statusText = QStringLiteral("操作未发送，可使用相同期望状态重试");
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
    m_statusText = blocked ? QStringLiteral("已屏蔽该账号")
                           : QStringLiteral("已取消屏蔽该账号");
    m_clientOperationId.clear();
    m_operationTargetAccountId.clear();
    emit changed();
}

void V2WindowsAccountBlockViewModel::applyFailure(
        const QString &clientOperationId, bool retryable) {
    if (m_state != State::Pending || clientOperationId != m_clientOperationId)
        throw std::runtime_error("uncorrelated account block view failure");
    m_state = State::Failed;
    m_statusText = retryable
        ? QStringLiteral("操作暂未完成，可重试")
        : QStringLiteral("无法完成该操作");
    if (!retryable) {
        m_clientOperationId.clear();
        m_operationTargetAccountId.clear();
    }
    emit changed();
}

void V2WindowsAccountBlockViewModel::setUnavailable() {
    if (m_state == State::Pending) {
        m_state = State::Failed;
        m_statusText = QStringLiteral("连接已断开，可在重连后重试");
    } else {
        m_state = State::Unavailable;
        m_statusText = QStringLiteral("屏蔽服务已断开，正在重连");
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
