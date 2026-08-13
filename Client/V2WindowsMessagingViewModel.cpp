#include "V2WindowsMessagingViewModel.h"

#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsMessagingViewModel::V2WindowsMessagingViewModel(
        QString accountId, SnapshotLoader loader, StageReply stageReply,
        Retry retry, QObject *parent)
    : QObject(parent), m_accountId(std::move(accountId)), m_loader(std::move(loader)),
      m_stageReply(std::move(stageReply)), m_retry(std::move(retry)) {
    if (m_accountId.isEmpty() || !m_loader || !m_stageReply || !m_retry)
        throw std::invalid_argument("invalid Windows V2 messaging view model");
}

bool V2WindowsMessagingViewModel::openConversation(const QString &conversationId) {
    if (conversationId.isEmpty()) return false;
    if (conversationId != m_conversationId) cancelReply();
    m_conversationId = conversationId;
    return refresh();
}

bool V2WindowsMessagingViewModel::refresh() {
    if (m_conversationId.isEmpty()) return false;
    try {
        project(m_loader(m_conversationId));
    } catch (...) {
        m_failure = QStringLiteral("无法加载本地消息");
        emit changed();
        return false;
    }
    m_failure.clear();
    emit changed();
    return true;
}

bool V2WindowsMessagingViewModel::chooseReply(const QString &messageId) {
    const auto position = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.messageId == messageId && row.canReply; });
    if (position == m_rows.cend()) return false;
    m_replyTargetMessageId = messageId;
    m_replyBanner = QStringLiteral("回复 %1").arg(
        position->text.left(80).replace(QLatin1Char('\n'), QLatin1Char(' ')));
    m_failure.clear();
    emit changed();
    emit focusComposerRequested();
    return true;
}

void V2WindowsMessagingViewModel::cancelReply() {
    if (m_replyTargetMessageId.isEmpty() && m_replyBanner.isEmpty()) return;
    m_replyTargetMessageId.clear();
    m_replyBanner.clear();
    emit changed();
    emit focusComposerRequested();
}

bool V2WindowsMessagingViewModel::sendReply(const QString &text) {
    if (m_conversationId.isEmpty() || m_replyTargetMessageId.isEmpty()
            || text.trimmed().isEmpty()) return false;
    V2LocalMessageRepository::Message optimistic;
    if (!m_stageReply(m_conversationId, m_replyTargetMessageId, text, &optimistic)) {
        m_failure = QStringLiteral("无法发送回复");
        emit changed();
        return false;
    }
    m_replyTargetMessageId.clear();
    m_replyBanner.clear();
    return refresh();
}

bool V2WindowsMessagingViewModel::retry(const QString &clientMessageId) {
    const auto position = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.clientMessageId == clientMessageId && row.canRetry; });
    if (position == m_rows.cend() || !m_retry(m_conversationId, clientMessageId)) {
        m_failure = QStringLiteral("无法重试该消息");
        emit changed();
        return false;
    }
    return refresh();
}

void V2WindowsMessagingViewModel::project(
        const V2LocalMessageRepository::Snapshot &snapshot) {
    m_rows.clear();
    m_rows.reserve(snapshot.messages.size());
    m_draft = snapshot.draft;
    for (const auto &message : snapshot.messages) {
        Row row;
        row.messageId = message.messageId;
        row.clientMessageId = message.clientMessageId;
        row.text = message.recalled ? QStringLiteral("此消息已被撤回") : message.text;
        row.senderAccountId = message.senderAccountId;
        row.mine = message.senderAccountId == m_accountId;
        row.recalled = message.recalled;
        row.canReply = !message.recalled && !message.messageId.isEmpty()
            && message.state == V2LocalMessageRepository::DeliveryState::Accepted;
        row.canRetry = message.state == V2LocalMessageRepository::DeliveryState::Failed;
        if (message.state == V2LocalMessageRepository::DeliveryState::Pending)
            row.deliveryLabel = QStringLiteral("发送中…");
        else if (message.state == V2LocalMessageRepository::DeliveryState::Failed)
            row.deliveryLabel = QStringLiteral("发送失败");
        row.replyPreview = previewFor(message, snapshot);
        m_rows.append(std::move(row));
    }
    if (!m_replyTargetMessageId.isEmpty()) {
        const bool available = std::any_of(m_rows.cbegin(), m_rows.cend(),
            [&](const Row &row) { return row.messageId == m_replyTargetMessageId && row.canReply; });
        if (!available) {
            m_replyTargetMessageId.clear();
            m_replyBanner.clear();
        }
    }
}

QString V2WindowsMessagingViewModel::previewFor(
        const V2LocalMessageRepository::Message &message,
        const V2LocalMessageRepository::Snapshot &snapshot) const {
    if (!message.hasReply) return {};
    const auto target = std::find_if(snapshot.messages.cbegin(), snapshot.messages.cend(),
        [&](const auto &candidate) { return candidate.messageId == message.reply.targetMessageId; });
    if (target == snapshot.messages.cend()) return QStringLiteral("引用的消息不可用");
    if (target->recalled) return QStringLiteral("引用的消息已撤回");
    return target->text.left(120).replace(QLatin1Char('\n'), QLatin1Char(' '));
}
