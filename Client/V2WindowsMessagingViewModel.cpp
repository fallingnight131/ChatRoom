#include "V2WindowsMessagingViewModel.h"

#include <algorithm>
#include <stdexcept>
#include <utility>

V2WindowsMessagingViewModel::V2WindowsMessagingViewModel(
        QString accountId, SnapshotLoader loader, StageReply stageReply,
        Retry retry, SetReaction setReaction, RetryReaction retryReaction,
        SetPin setPin, RetryPin retryPin, Edit edit, EditOperation retryEdit,
        EditOperation rebaseEdit, DiscardEdit discardEdit, QObject *parent)
    : QObject(parent), m_accountId(std::move(accountId)), m_loader(std::move(loader)),
      m_stageReply(std::move(stageReply)), m_retry(std::move(retry)),
      m_setReaction(std::move(setReaction)), m_retryReaction(std::move(retryReaction)),
      m_setPin(std::move(setPin)), m_retryPin(std::move(retryPin)),
      m_edit(std::move(edit)), m_retryEdit(std::move(retryEdit)),
      m_rebaseEdit(std::move(rebaseEdit)), m_discardEdit(std::move(discardEdit)) {
    if (m_accountId.isEmpty() || !m_loader || !m_stageReply || !m_retry
            || !m_setReaction || !m_retryReaction || !m_setPin || !m_retryPin
            || !m_edit || !m_retryEdit || !m_rebaseEdit || !m_discardEdit)
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

bool V2WindowsMessagingViewModel::setReaction(
        const QString &messageId, V2LocalMessageRepository::ReactionKind reaction) {
    const auto position = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.messageId == messageId && row.canReply; });
    if (position == m_rows.cend() || !m_setReaction(m_conversationId, messageId, reaction)) {
        m_failure = QStringLiteral("无法更新消息反应"); emit changed(); return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::retryReaction(const QString &clientOperationId) {
    if (!m_retryReaction(m_conversationId, clientOperationId)) {
        m_failure = QStringLiteral("无法重试消息反应"); emit changed(); return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::setPin(const QString &messageId) {
    const auto position = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.messageId == messageId && row.canReply; });
    if (position == m_rows.cend() || !m_setPin(m_conversationId, messageId)) {
        m_failure = QStringLiteral("无法更新置顶状态"); emit changed(); return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::retryPin(const QString &clientOperationId) {
    if (!m_retryPin(m_conversationId, clientOperationId)) {
        m_failure = QStringLiteral("无法重试置顶操作"); emit changed(); return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::editMessage(const QString &messageId, const QString &text) {
    const auto position = std::find_if(m_rows.cbegin(), m_rows.cend(),
        [&](const Row &row) { return row.messageId == messageId && row.canEdit; });
    if (position == m_rows.cend() || text.trimmed().isEmpty()
            || !m_edit(m_conversationId, messageId, text)) {
        m_failure = QStringLiteral("无法编辑该消息");
        emit changed();
        return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::retryEdit(const QString &operationId) {
    if (!m_retryEdit(m_conversationId, operationId)) {
        m_failure = QStringLiteral("无法重试编辑");
        emit changed();
        return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::rebaseEdit(const QString &operationId) {
    if (!m_rebaseEdit(m_conversationId, operationId)) {
        m_failure = QStringLiteral("新版本尚未同步");
        emit changed();
        return false;
    }
    return refresh();
}

bool V2WindowsMessagingViewModel::discardEdit(const QString &operationId) {
    if (!m_discardEdit(operationId)) {
        m_failure = QStringLiteral("无法放弃编辑草稿");
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
        row.pinned = message.pinned;
        row.edited = message.contentRevision > 0;
        row.canEdit = row.mine && row.canReply;
        const auto editCommand = std::find_if(
            snapshot.editCommands.cbegin(), snapshot.editCommands.cend(),
            [&](const auto &item) { return item.messageId == message.messageId; });
        if (editCommand != snapshot.editCommands.cend()) {
            row.canEdit = false;
            row.editOperationId = editCommand->clientOperationId;
            row.proposedText = editCommand->proposedText;
            row.text = editCommand->proposedText;
            row.editPending = editCommand->state
                == V2LocalMessageRepository::EditDeliveryState::Pending;
            row.editFailed = editCommand->state
                == V2LocalMessageRepository::EditDeliveryState::Failed;
            row.editConflict = editCommand->state
                == V2LocalMessageRepository::EditDeliveryState::Conflict;
        }
        const auto pinCommand = std::find_if(snapshot.pinCommands.cbegin(),
            snapshot.pinCommands.cend(), [&](const auto &item) {
                return item.messageId == message.messageId;
            });
        if (pinCommand != snapshot.pinCommands.cend()) {
            row.pinPending = pinCommand->state
                == V2LocalMessageRepository::DeliveryState::Pending;
            row.pinFailed = pinCommand->state
                == V2LocalMessageRepository::DeliveryState::Failed;
            row.pinOperationId = pinCommand->clientOperationId;
        }
        if (message.state == V2LocalMessageRepository::DeliveryState::Pending)
            row.deliveryLabel = QStringLiteral("发送中…");
        else if (message.state == V2LocalMessageRepository::DeliveryState::Failed)
            row.deliveryLabel = QStringLiteral("发送失败");
        row.replyPreview = previewFor(message, snapshot);
        for (int value = static_cast<int>(V2LocalMessageRepository::ReactionKind::Like);
             value <= static_cast<int>(V2LocalMessageRepository::ReactionKind::Angry); ++value) {
            Row::Reaction projected;
            projected.kind = static_cast<V2LocalMessageRepository::ReactionKind>(value);
            const auto aggregate = std::find_if(message.reactions.cbegin(), message.reactions.cend(),
                [&](const auto &item) { return item.reaction == projected.kind; });
            if (aggregate != message.reactions.cend()) {
                projected.count = aggregate->actorAccountIds.size();
                projected.mine = aggregate->actorAccountIds.contains(m_accountId);
            }
            const auto command = std::find_if(snapshot.reactionCommands.cbegin(),
                snapshot.reactionCommands.cend(), [&](const auto &item) {
                    return item.messageId == message.messageId && item.reaction == projected.kind;
                });
            if (command != snapshot.reactionCommands.cend()) {
                projected.pending = command->state == V2LocalMessageRepository::DeliveryState::Pending;
                projected.failed = command->state == V2LocalMessageRepository::DeliveryState::Failed;
                projected.clientOperationId = command->clientOperationId;
            }
            row.reactions.append(projected);
        }
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
