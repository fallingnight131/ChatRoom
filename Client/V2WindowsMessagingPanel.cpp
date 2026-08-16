#include "V2WindowsMessagingPanel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsForwardTargetDialog.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"

#include <QHBoxLayout>
#include <QAccessible>
#include <QLabel>
#include <QListWidget>
#include <QLineEdit>
#include <QPalette>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTextCursor>
#include <QVBoxLayout>
#include <stdexcept>

V2WindowsMessagingPanel::V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent, bool mentionsEnabled,
        V2WindowsConversationDirectoryViewModel *directoryViewModel,
        bool forwardingEnabled,
        V2WindowsMessageSearchViewModel *searchViewModel)
    : QWidget(parent), m_viewModel(viewModel),
      m_participantViewModel(participantViewModel),
      m_directoryViewModel(directoryViewModel), m_searchViewModel(searchViewModel),
      m_status(new QLabel(this)), m_searchPane(new QWidget(this)),
      m_searchInput(new QLineEdit(m_searchPane)),
      m_searchButton(new QPushButton(QStringLiteral("搜索"), m_searchPane)),
      m_searchStatus(new QLabel(m_searchPane)),
      m_searchResults(new QListWidget(m_searchPane)),
      m_searchLoadMore(new QPushButton(QStringLiteral("加载更多结果"), m_searchPane)),
      m_replyBanner(new QLabel(this)), m_messages(new QListWidget(this)),
      m_composer(new QPlainTextEdit(this)),
      m_participantPane(new QWidget(this)),
      m_participantStatus(new QLabel(m_participantPane)),
      m_participants(new QListWidget(m_participantPane)),
      m_refreshParticipants(new QPushButton(QStringLiteral("刷新成员"), m_participantPane)),
      m_loadMoreParticipants(new QPushButton(QStringLiteral("加载更多"), m_participantPane)),
      m_closeParticipants(new QPushButton(QStringLiteral("关闭"), m_participantPane)),
      m_cancelReply(new QPushButton(QStringLiteral("取消回复"), this)),
      m_mention(new QPushButton(QStringLiteral("@ 提及"), this)),
      m_send(new QPushButton(QStringLiteral("发送回复"), this)),
      m_mentionsEnabled(mentionsEnabled),
      m_forwardingEnabled(forwardingEnabled && directoryViewModel) {
    Q_ASSERT(m_viewModel);
    Q_ASSERT(m_participantViewModel);
    setAccessibleName(QStringLiteral("消息和回复"));
    m_status->setAccessibleName(QStringLiteral("消息状态"));
    m_status->setWordWrap(true);
    m_searchPane->setAccessibleName(QStringLiteral("会话消息搜索"));
    m_searchInput->setAccessibleName(QStringLiteral("搜索当前会话消息"));
    m_searchInput->setPlaceholderText(QStringLiteral("输入 1 至 128 字节的文字"));
    m_searchInput->setMaxLength(128);
    m_searchButton->setAccessibleName(QStringLiteral("提交当前会话搜索"));
    m_searchStatus->setAccessibleName(QStringLiteral("搜索结果状态"));
    m_searchStatus->setWordWrap(true);
    m_searchResults->setAccessibleName(QStringLiteral("搜索结果列表"));
    m_searchResults->setSelectionMode(QAbstractItemView::SingleSelection);
    m_searchLoadMore->setAccessibleName(QStringLiteral("加载更多搜索结果"));
    auto *searchControls = new QHBoxLayout;
    searchControls->addWidget(m_searchInput, 1);
    searchControls->addWidget(m_searchButton);
    auto *searchLayout = new QVBoxLayout(m_searchPane);
    searchLayout->addLayout(searchControls);
    searchLayout->addWidget(m_searchStatus);
    searchLayout->addWidget(m_searchResults);
    searchLayout->addWidget(m_searchLoadMore);
    m_searchPane->setVisible(m_searchViewModel != nullptr);
    m_replyBanner->setAccessibleName(QStringLiteral("当前回复目标"));
    m_replyBanner->setWordWrap(true);
    m_messages->setAccessibleName(QStringLiteral("消息列表"));
    m_messages->setSelectionMode(QAbstractItemView::NoSelection);
    m_composer->setAccessibleName(QStringLiteral("回复内容"));
    m_composer->setPlaceholderText(QStringLiteral("输入回复内容"));
    m_composer->setMaximumBlockCount(1000);
    m_participantPane->setAccessibleName(QStringLiteral("会话成员选择器"));
    m_participantStatus->setAccessibleName(QStringLiteral("成员列表状态"));
    m_participantStatus->setWordWrap(true);
    m_participants->setAccessibleName(QStringLiteral("可提及的会话成员"));
    m_participants->setSelectionMode(QAbstractItemView::SingleSelection);
    m_refreshParticipants->setAccessibleName(QStringLiteral("刷新可提及成员"));
    m_loadMoreParticipants->setAccessibleName(QStringLiteral("加载更多可提及成员"));
    m_closeParticipants->setAccessibleName(QStringLiteral("关闭成员选择器"));
    m_mention->setAccessibleName(QStringLiteral("打开会话成员选择器"));
    m_mention->setVisible(m_mentionsEnabled);
    m_cancelReply->setAccessibleName(QStringLiteral("取消当前回复"));
    m_send->setAccessibleName(QStringLiteral("发送当前回复"));

    auto *participantButtons = new QHBoxLayout;
    participantButtons->addWidget(m_refreshParticipants);
    participantButtons->addWidget(m_loadMoreParticipants);
    participantButtons->addStretch();
    participantButtons->addWidget(m_closeParticipants);
    auto *participantLayout = new QVBoxLayout(m_participantPane);
    participantLayout->addWidget(m_participantStatus);
    participantLayout->addWidget(m_participants);
    participantLayout->addLayout(participantButtons);
    m_participantPane->setVisible(false);

    auto *replyHeader = new QHBoxLayout;
    replyHeader->addWidget(m_replyBanner, 1);
    replyHeader->addWidget(m_cancelReply);
    auto *composerRow = new QHBoxLayout;
    composerRow->addWidget(m_composer, 1);
    composerRow->addWidget(m_mention);
    composerRow->addWidget(m_send);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(m_searchPane);
    layout->addWidget(m_status);
    layout->addWidget(m_messages, 1);
    layout->addLayout(replyHeader);
    layout->addWidget(m_participantPane);
    layout->addLayout(composerRow);

    connect(m_viewModel, &V2WindowsMessagingViewModel::changed,
            this, &V2WindowsMessagingPanel::render);
    connect(m_viewModel, &V2WindowsMessagingViewModel::focusComposerRequested,
            m_composer, qOverload<>(&QWidget::setFocus));
    connect(m_participantViewModel,
            &V2WindowsConversationParticipantViewModel::changed,
            this, &V2WindowsMessagingPanel::renderParticipants);
    connect(m_cancelReply, &QPushButton::clicked,
            this, &V2WindowsMessagingPanel::cancelComposition);
    connect(m_send, &QPushButton::clicked, this, &V2WindowsMessagingPanel::sendReply);
    connect(m_mention, &QPushButton::clicked,
            this, &V2WindowsMessagingPanel::toggleParticipantPicker);
    connect(m_closeParticipants, &QPushButton::clicked,
            m_participantPane, &QWidget::hide);
    connect(m_refreshParticipants, &QPushButton::clicked,
            m_participantViewModel, &V2WindowsConversationParticipantViewModel::refresh);
    connect(m_loadMoreParticipants, &QPushButton::clicked,
            m_participantViewModel, &V2WindowsConversationParticipantViewModel::loadMore);
    connect(m_participants, &QListWidget::itemActivated,
            this, &V2WindowsMessagingPanel::insertParticipant);
    connect(m_composer, &QPlainTextEdit::textChanged,
            this, &V2WindowsMessagingPanel::reconcileComposer);
    if (m_searchViewModel) {
        connect(m_searchViewModel, &V2WindowsMessageSearchViewModel::changed,
                this, &V2WindowsMessagingPanel::renderSearch);
        connect(m_searchButton, &QPushButton::clicked,
                this, &V2WindowsMessagingPanel::startSearch);
        connect(m_searchInput, &QLineEdit::returnPressed,
                this, &V2WindowsMessagingPanel::startSearch);
        connect(m_searchLoadMore, &QPushButton::clicked,
                m_searchViewModel, &V2WindowsMessageSearchViewModel::loadMore);
        connect(m_searchResults, &QListWidget::itemActivated,
                this, &V2WindowsMessagingPanel::revealSearchResult);
    }
    render();
    renderParticipants();
    renderSearch();
}

void V2WindowsMessagingPanel::setConversation(const QString &conversationId) {
    if (conversationId == m_conversationId) return;
    m_conversationId = conversationId;
    m_editTargetMessageId.clear();
    m_participantPane->hide();
    m_mentionAnchors.clear();
    m_updatingComposer = true;
    m_composer->clear();
    m_updatingComposer = false;
    m_previousComposerText.clear();
    m_mention->setEnabled(false);
    if (m_searchViewModel) {
        m_searchViewModel->activate(conversationId);
        m_searchInput->clear();
        renderSearch();
    }
}

void V2WindowsMessagingPanel::render() {
    m_status->setText(m_viewModel->failure());
    m_messages->clear();
    for (const auto &message : m_viewModel->rows()) {
        auto *item = new QListWidgetItem(m_messages);
        item->setData(Qt::UserRole, message.messageId);
        auto *row = new QWidget(m_messages);
        auto *layout = new QVBoxLayout(row);
        auto *body = new QLabel(row);
        QString richBody;
        QStringList mentionTargetAccountIds;
        const QString mentionColor = palette().color(QPalette::Link).name();
        for (const auto &segment : V2WindowsMentionComposer::segments(
                 message.text, message.mentions)) {
            const QString escaped = segment.text.toHtmlEscaped().replace(
                QStringLiteral("\n"), QStringLiteral("<br>"));
            if (segment.mention) {
                richBody += QStringLiteral(
                    "<span style=\"color:%1;font-weight:600\">%2</span>")
                    .arg(mentionColor, escaped);
                mentionTargetAccountIds.append(segment.targetAccountId);
            } else {
                richBody += escaped;
            }
        }
        body->setText(richBody);
        body->setTextFormat(Qt::RichText);
        body->setAccessibleName(QStringLiteral("消息内容：%1").arg(message.text));
        body->setProperty("mentionTargetAccountIds", mentionTargetAccountIds);
        body->setWordWrap(true);
        body->setTextInteractionFlags(Qt::TextSelectableByKeyboard | Qt::TextSelectableByMouse);
        if (!message.replyPreview.isEmpty()) {
            auto *reference = new QLabel(
                QStringLiteral("引用：%1").arg(message.replyPreview), row);
            reference->setAccessibleName(QStringLiteral("引用消息"));
            reference->setWordWrap(true);
            layout->addWidget(reference);
        }
        layout->addWidget(body);
        if (message.forwarded) {
            auto *forwarded = new QLabel(QStringLiteral("已转发"), row);
            forwarded->setAccessibleName(QStringLiteral("此消息由服务器转发"));
            layout->addWidget(forwarded);
        }
        if (message.edited) {
            auto *edited = new QLabel(QStringLiteral("已编辑"), row);
            edited->setAccessibleName(QStringLiteral("此消息已编辑"));
            layout->addWidget(edited);
        }
        if (message.editPending) {
            auto *status = new QLabel(QStringLiteral("正在保存编辑…"), row);
            status->setAccessibleName(QStringLiteral("编辑状态：正在保存"));
            layout->addWidget(status);
        } else if (message.editConflict || message.editFailed) {
            auto *status = new QLabel(message.editConflict
                ? QStringLiteral("其他设备已修改此消息；你的编辑草稿已保留")
                : QStringLiteral("编辑保存失败；草稿仍保存在本机"), row);
            status->setAccessibleName(message.editConflict
                ? QStringLiteral("编辑冲突") : QStringLiteral("编辑失败"));
            status->setWordWrap(true);
            layout->addWidget(status);
            auto *editActions = new QHBoxLayout;
            auto *retry = new QPushButton(message.editConflict
                ? QStringLiteral("基于新版本重试") : QStringLiteral("重试编辑"), row);
            retry->setAccessibleName(message.editConflict
                ? QStringLiteral("基于服务器新版本重试编辑") : QStringLiteral("重试保存编辑"));
            connect(retry, &QPushButton::clicked, m_viewModel,
                [model = m_viewModel, id = message.editOperationId,
                 conflict = message.editConflict] {
                    if (conflict) model->rebaseEdit(id);
                    else model->retryEdit(id);
                });
            auto *discard = new QPushButton(QStringLiteral("放弃草稿"), row);
            discard->setAccessibleName(QStringLiteral("放弃此消息的编辑草稿"));
            connect(discard, &QPushButton::clicked, m_viewModel,
                [model = m_viewModel, id = message.editOperationId] {
                    model->discardEdit(id);
                });
            editActions->addWidget(retry);
            editActions->addWidget(discard);
            editActions->addStretch();
            layout->addLayout(editActions);
        }
        auto *actions = new QHBoxLayout;
        if (!message.deliveryLabel.isEmpty()) {
            auto *delivery = new QLabel(message.deliveryLabel, row);
            delivery->setAccessibleName(QStringLiteral("发送状态"));
            actions->addWidget(delivery);
        }
        actions->addStretch();
        if (message.canRetry) {
            auto *retry = new QPushButton(QStringLiteral("重试"), row);
            retry->setAccessibleName(QStringLiteral("重试发送此消息"));
            connect(retry, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.clientMessageId] { model->retry(id); });
            actions->addWidget(retry);
        }
        if (message.canReply) {
            if (m_forwardingEnabled && message.canForward) {
                auto *forward = new QPushButton(QStringLiteral("转发"), row);
                forward->setAccessibleName(QStringLiteral("转发此消息"));
                connect(forward, &QPushButton::clicked, this,
                    [this, id = message.messageId] { chooseForward(id); });
                actions->addWidget(forward);
            }
            auto *reply = new QPushButton(QStringLiteral("回复"), row);
            reply->setAccessibleName(QStringLiteral("回复此消息"));
            connect(reply, &QPushButton::clicked, this,
                    [this, id = message.messageId] { chooseReply(id); });
            actions->addWidget(reply);
            if (message.canEdit) {
                auto *edit = new QPushButton(QStringLiteral("编辑"), row);
                edit->setAccessibleName(QStringLiteral("编辑此消息"));
                connect(edit, &QPushButton::clicked, this,
                    [this, id = message.messageId, current = message.text,
                     mentions = message.mentions] {
                        beginEdit(id, current, mentions);
                    });
                actions->addWidget(edit);
            }
            auto *pin = new QPushButton(row);
            pin->setCheckable(true);
            pin->setChecked(message.pinned);
            pin->setEnabled(!message.pinPending);
            pin->setText(message.pinFailed
                ? QStringLiteral("置顶失败，重试")
                : (message.pinned ? QStringLiteral("取消置顶") : QStringLiteral("置顶")));
            pin->setAccessibleName(message.pinFailed
                ? QStringLiteral("重试此消息的置顶操作")
                : (message.pinned ? QStringLiteral("取消置顶此消息")
                                  : QStringLiteral("置顶此消息")));
            if (message.pinFailed) {
                connect(pin, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.pinOperationId] { model->retryPin(id); });
            } else {
                connect(pin, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.messageId] { model->setPin(id); });
            }
            actions->addWidget(pin);
        }
        layout->addLayout(actions);
        if (message.canReply) {
            auto *reactions = new QHBoxLayout;
            static const QStringList labels{
                QStringLiteral("👍"), QStringLiteral("❤"), QStringLiteral("😂"),
                QStringLiteral("😮"), QStringLiteral("😢"), QStringLiteral("😠")};
            for (const auto &reaction : message.reactions) {
                const int index = static_cast<int>(reaction.kind) - 1;
                auto *button = new QPushButton(
                    QStringLiteral("%1 %2").arg(labels.value(index)).arg(reaction.count), row);
                button->setCheckable(true);
                button->setChecked(reaction.mine);
                button->setEnabled(!reaction.pending);
                button->setAccessibleName(QStringLiteral("消息反应 %1，%2 人")
                    .arg(labels.value(index)).arg(reaction.count));
                if (reaction.failed) {
                    button->setText(QStringLiteral("%1 重试").arg(labels.value(index)));
                    connect(button, &QPushButton::clicked, m_viewModel,
                        [model = m_viewModel, id = reaction.clientOperationId] {
                            model->retryReaction(id);
                        });
                } else {
                    connect(button, &QPushButton::clicked, m_viewModel,
                        [model = m_viewModel, id = message.messageId, kind = reaction.kind] {
                            model->setReaction(id, kind);
                        });
                }
                reactions->addWidget(button);
            }
            reactions->addStretch();
            layout->addLayout(reactions);
        }
        item->setSizeHint(row->sizeHint());
        m_messages->setItemWidget(item, row);
    }
    const bool editing = !m_editTargetMessageId.isEmpty();
    const bool replying = !m_viewModel->replyTargetMessageId().isEmpty();
    const bool composing = replying || editing;
    m_replyBanner->setText(editing
        ? QStringLiteral("正在编辑消息") : m_viewModel->replyBanner());
    m_replyBanner->setVisible(composing);
    m_cancelReply->setText(editing ? QStringLiteral("取消编辑") : QStringLiteral("取消回复"));
    m_cancelReply->setAccessibleName(editing
        ? QStringLiteral("取消当前编辑") : QStringLiteral("取消当前回复"));
    m_cancelReply->setVisible(composing);
    m_send->setText(editing ? QStringLiteral("保存编辑") : QStringLiteral("发送回复"));
    m_send->setAccessibleName(editing
        ? QStringLiteral("保存当前消息编辑") : QStringLiteral("发送当前回复"));
    m_mention->setEnabled(
        m_mentionsEnabled && composing && !m_conversationId.isEmpty());
    m_send->setEnabled(composing && !m_composer->toPlainText().trimmed().isEmpty());
}

void V2WindowsMessagingPanel::startSearch() {
    if (!m_searchViewModel || m_conversationId.isEmpty()) return;
    if (!m_searchViewModel->search(m_searchInput->text())) {
        m_searchStatus->setText(QStringLiteral("请输入有效的搜索文字"));
    }
}

void V2WindowsMessagingPanel::renderSearch() {
    if (!m_searchViewModel) return;
    m_searchResults->clear();
    for (const auto &row : m_searchViewModel->rows()) {
        auto *item = new QListWidgetItem(
            QStringLiteral("#%1  %2").arg(row.conversationSequence).arg(row.text),
            m_searchResults);
        item->setData(Qt::UserRole, row.messageId);
        item->setData(Qt::AccessibleDescriptionRole,
                      QStringLiteral("激活以定位到该消息"));
        item->setToolTip(row.text);
    }
    m_searchButton->setEnabled(!m_searchViewModel->busy());
    m_searchInput->setEnabled(!m_searchViewModel->busy());
    m_searchLoadMore->setEnabled(
        !m_searchViewModel->busy() && m_searchViewModel->hasMore());
    if (!m_searchViewModel->failure().isEmpty())
        m_searchStatus->setText(m_searchViewModel->failure());
    else if (m_searchViewModel->busy())
        m_searchStatus->setText(QStringLiteral("正在搜索…"));
    else if (!m_searchViewModel->query().isEmpty())
        m_searchStatus->setText(QStringLiteral("已找到 %1 条结果")
            .arg(m_searchViewModel->rows().size()));
    else
        m_searchStatus->setText(QStringLiteral("搜索结果仅保留在当前页面"));
    if (m_searchPane->isVisible()) {
        QAccessibleEvent announcement(m_searchStatus, QAccessible::Alert);
        QAccessible::updateAccessibility(&announcement);
    }
}

void V2WindowsMessagingPanel::revealSearchResult(QListWidgetItem *item) {
    if (!item) return;
    if (!revealMessage(item->data(Qt::UserRole).toString()))
        m_searchStatus->setText(QStringLiteral("正在请求该消息附近的上下文"));
}

bool V2WindowsMessagingPanel::revealMessage(const QString &messageId) {
    for (int row = 0; row < m_messages->count(); ++row) {
        auto *item = m_messages->item(row);
        if (item->data(Qt::UserRole).toString() != messageId) continue;
        m_messages->setCurrentItem(item);
        m_messages->scrollToItem(item, QAbstractItemView::PositionAtCenter);
        m_messages->setFocus();
        return true;
    }
    return false;
}

void V2WindowsMessagingPanel::chooseForward(const QString &messageId) {
    if (!m_forwardingEnabled || !m_directoryViewModel
            || m_conversationId.isEmpty() || messageId.isEmpty()) return;
    V2WindowsForwardTargetDialog dialog(
        m_directoryViewModel->rows(), m_conversationId, this, true);
    if (dialog.exec() != QDialog::Accepted) return;
    m_viewModel->forwardMessage(messageId, dialog.selectedConversationId());
}

void V2WindowsMessagingPanel::renderParticipants() {
    m_participants->clear();
    for (const auto &row : m_participantViewModel->rows()) {
        auto *item = new QListWidgetItem(
            row.roleLabel.isEmpty()
                ? row.displayName
                : QStringLiteral("%1 · %2").arg(row.displayName, row.roleLabel),
            m_participants);
        item->setData(Qt::UserRole, row.accountId);
        item->setData(Qt::UserRole + 1, row.displayName);
        item->setToolTip(row.displayName);
    }
    m_refreshParticipants->setEnabled(!m_participantViewModel->busy());
    m_loadMoreParticipants->setEnabled(
        !m_participantViewModel->busy() && m_participantViewModel->hasMore());
    if (!m_participantViewModel->failure().isEmpty())
        m_participantStatus->setText(m_participantViewModel->failure());
    else if (m_participantViewModel->busy())
        m_participantStatus->setText(QStringLiteral("正在加载会话成员…"));
    else if (m_participantViewModel->rows().isEmpty())
        m_participantStatus->setText(QStringLiteral("没有可提及的成员"));
    else
        m_participantStatus->setText(QStringLiteral("选择成员并按 Enter 插入提及"));
}

void V2WindowsMessagingPanel::chooseReply(const QString &messageId) {
    if (messageId.isEmpty()) return;
    if (!m_editTargetMessageId.isEmpty()) {
        m_editTargetMessageId.clear();
        m_mentionAnchors.clear();
        m_updatingComposer = true;
        m_composer->clear();
        m_updatingComposer = false;
        m_previousComposerText.clear();
    }
    m_viewModel->chooseReply(messageId);
}

void V2WindowsMessagingPanel::beginEdit(
        const QString &messageId, const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    try {
        const auto anchors = V2WindowsMentionComposer::restore(text, mentions);
        m_viewModel->cancelReply();
        m_editTargetMessageId = messageId;
        m_updatingComposer = true;
        m_composer->setPlainText(text);
        m_updatingComposer = false;
        m_previousComposerText = text;
        m_mentionAnchors = anchors;
        render();
        m_composer->setFocus();
    } catch (const std::exception &) {
        m_status->setText(QStringLiteral("无法恢复消息中的提及，暂不能编辑"));
    }
}

void V2WindowsMessagingPanel::cancelComposition() {
    const bool editing = !m_editTargetMessageId.isEmpty();
    m_editTargetMessageId.clear();
    m_participantPane->hide();
    if (editing) {
        m_updatingComposer = true;
        m_composer->clear();
        m_updatingComposer = false;
        m_previousComposerText.clear();
        m_mentionAnchors.clear();
    }
    m_viewModel->cancelReply();
    render();
}

void V2WindowsMessagingPanel::toggleParticipantPicker() {
    if (!m_mentionsEnabled || m_conversationId.isEmpty()) return;
    if (m_participantPane->isVisible()) {
        m_participantPane->hide();
        m_composer->setFocus();
        return;
    }
    m_participantPane->show();
    if (m_participantViewModel->conversationId() != m_conversationId)
        m_participantViewModel->activate(m_conversationId);
    else if (m_participantViewModel->rows().isEmpty()
             && !m_participantViewModel->busy())
        m_participantViewModel->refresh();
    renderParticipants();
    if (m_participants->count() > 0) {
        m_participants->setCurrentRow(0);
        m_participants->setFocus();
    } else {
        m_refreshParticipants->setFocus();
    }
}

void V2WindowsMessagingPanel::insertParticipant(QListWidgetItem *item) {
    if (!item) return;
    const QTextCursor cursor = m_composer->textCursor();
    try {
        const auto edit = V2WindowsMentionComposer::insert(
            m_composer->toPlainText(), m_mentionAnchors,
            cursor.selectionStart(), cursor.selectionEnd(),
            {item->data(Qt::UserRole).toString(),
             item->data(Qt::UserRole + 1).toString()});
        m_updatingComposer = true;
        QTextCursor next = cursor;
        next.beginEditBlock();
        next.insertText(QStringLiteral("@")
            + item->data(Qt::UserRole + 1).toString() + QStringLiteral(" "));
        next.endEditBlock();
        next.setPosition(edit.caretUtf16);
        m_composer->setTextCursor(next);
        m_mentionAnchors = edit.anchors;
        m_previousComposerText = edit.text;
        m_updatingComposer = false;
        m_participantPane->hide();
        m_composer->setFocus();
    } catch (const std::exception &) {
        m_status->setText(QStringLiteral("无法插入提及，请刷新成员列表后重试"));
    }
}

void V2WindowsMessagingPanel::reconcileComposer() {
    const QString next = m_composer->toPlainText();
    if (!m_updatingComposer) {
        try {
            m_mentionAnchors = V2WindowsMentionComposer::reconcile(
                m_previousComposerText, next, m_mentionAnchors);
        } catch (const std::exception &) {
            m_mentionAnchors.clear();
        }
    }
    m_previousComposerText = next;
    m_send->setEnabled((!m_viewModel->replyTargetMessageId().isEmpty()
        || !m_editTargetMessageId.isEmpty()) && !next.trimmed().isEmpty());
}

void V2WindowsMessagingPanel::sendReply() {
    QList<V2LocalMessageRepository::Mention> mentions;
    try {
        mentions = V2WindowsMentionComposer::serialize(
            m_composer->toPlainText(), m_mentionAnchors);
    } catch (const std::exception &) {
        m_status->setText(QStringLiteral("提及内容已失效，请重新选择成员"));
        return;
    }
    const bool accepted = m_editTargetMessageId.isEmpty()
        ? m_viewModel->sendReply(m_composer->toPlainText(), mentions)
        : m_viewModel->editMessage(
            m_editTargetMessageId, m_composer->toPlainText(), mentions);
    if (accepted) {
        m_editTargetMessageId.clear();
        m_composer->clear();
        m_mentionAnchors.clear();
        m_previousComposerText.clear();
        m_composer->setFocus();
    }
}
