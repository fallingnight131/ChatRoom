#include "V2WindowsMessagingPanel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsForwardTargetDialog.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"

#include <QAccessible>
#include <QClipboard>
#include <QEvent>
#include <QGuiApplication>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QListWidget>
#include <QLineEdit>
#include <QPalette>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTextCursor>
#include <QTimer>
#include <QVBoxLayout>
#include <stdexcept>

V2WindowsMessagingPanel::V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent, bool mentionsEnabled,
        V2WindowsConversationDirectoryViewModel *directoryViewModel,
        bool forwardingEnabled,
        V2WindowsMessageSearchViewModel *searchViewModel,
        WindowsLocale locale)
    : QWidget(parent), m_viewModel(viewModel),
      m_participantViewModel(participantViewModel),
      m_directoryViewModel(directoryViewModel), m_searchViewModel(searchViewModel),
      m_status(new QLabel(this)), m_searchPane(new QWidget(this)),
      m_searchInput(new QLineEdit(m_searchPane)),
      m_searchButton(new QPushButton(WindowsLocaleCatalog::messages(locale).search, m_searchPane)),
      m_searchStatus(new QLabel(m_searchPane)),
      m_searchResults(new QListWidget(m_searchPane)),
      m_searchLoadMore(new QPushButton(WindowsLocaleCatalog::messages(locale).loadMoreResults, m_searchPane)),
      m_replyBanner(new QLabel(this)), m_messages(new QListWidget(this)),
      m_composer(new QPlainTextEdit(this)),
      m_participantPane(new QWidget(this)),
      m_participantStatus(new QLabel(m_participantPane)),
      m_participants(new QListWidget(m_participantPane)),
      m_refreshParticipants(new QPushButton(WindowsLocaleCatalog::messages(locale).refreshParticipants, m_participantPane)),
      m_loadMoreParticipants(new QPushButton(WindowsLocaleCatalog::messages(locale).loadMore, m_participantPane)),
      m_closeParticipants(new QPushButton(WindowsLocaleCatalog::messages(locale).close, m_participantPane)),
      m_cancelReply(new QPushButton(WindowsLocaleCatalog::messages(locale).cancelReply, this)),
      m_mention(new QPushButton(WindowsLocaleCatalog::messages(locale).mention, this)),
      m_send(new QPushButton(WindowsLocaleCatalog::messages(locale).sendMessage, this)),
      m_composerBudget(new QLabel(this)),
      m_draftSaveTimer(new QTimer(this)),
      m_mentionsEnabled(mentionsEnabled),
      m_forwardingEnabled(forwardingEnabled && directoryViewModel),
      m_locale(locale) {
    Q_ASSERT(m_viewModel);
    Q_ASSERT(m_participantViewModel);
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setAccessibleName(copy.messagePanel);
    m_status->setAccessibleName(copy.messageStatusAccessible);
    m_status->setWordWrap(true);
    m_searchPane->setAccessibleName(copy.searchPaneAccessible);
    m_searchInput->setAccessibleName(copy.searchInputAccessible);
    m_searchInput->setPlaceholderText(copy.searchPlaceholder);
    m_searchInput->setMaxLength(128);
    m_searchButton->setAccessibleName(copy.searchSubmitAccessible);
    m_searchStatus->setAccessibleName(copy.searchStatusAccessible);
    m_searchStatus->setWordWrap(true);
    m_searchResults->setAccessibleName(copy.searchResultsAccessible);
    m_searchResults->setSelectionMode(QAbstractItemView::SingleSelection);
    m_searchLoadMore->setAccessibleName(copy.searchLoadMoreAccessible);
    auto *searchControls = new QHBoxLayout;
    searchControls->addWidget(m_searchInput, 1);
    searchControls->addWidget(m_searchButton);
    auto *searchLayout = new QVBoxLayout(m_searchPane);
    searchLayout->addLayout(searchControls);
    searchLayout->addWidget(m_searchStatus);
    searchLayout->addWidget(m_searchResults);
    searchLayout->addWidget(m_searchLoadMore);
    m_searchPane->setVisible(m_searchViewModel != nullptr);
    m_replyBanner->setAccessibleName(copy.replyTargetAccessible);
    m_replyBanner->setWordWrap(true);
    m_messages->setAccessibleName(copy.messageList);
    m_messages->setSelectionMode(QAbstractItemView::NoSelection);
    m_composer->setAccessibleName(copy.composer);
    m_composer->setPlaceholderText(copy.composerPlaceholder);
    m_composer->setMaximumBlockCount(1000);
    m_composer->installEventFilter(this);
    m_participantPane->setAccessibleName(copy.participantPaneAccessible);
    m_participantStatus->setAccessibleName(copy.participantStatusAccessible);
    m_participantStatus->setWordWrap(true);
    m_participants->setAccessibleName(copy.participantListAccessible);
    m_participants->setSelectionMode(QAbstractItemView::SingleSelection);
    m_refreshParticipants->setAccessibleName(copy.refreshParticipantsAccessible);
    m_loadMoreParticipants->setAccessibleName(copy.loadMoreParticipantsAccessible);
    m_closeParticipants->setAccessibleName(copy.closeParticipantsAccessible);
    m_mention->setAccessibleName(copy.mentionAccessible);
    m_mention->setVisible(m_mentionsEnabled);
    m_cancelReply->setAccessibleName(copy.cancelReplyAccessible);
    m_cancelReply->setToolTip(QStringLiteral("Esc"));
    m_send->setAccessibleName(copy.sendMessageAccessible);
    m_send->setToolTip(QStringLiteral("Ctrl+Enter"));
    m_composerBudget->setAccessibleName(copy.composerBudgetAccessible);
    m_composerBudget->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
    m_draftSaveTimer->setSingleShot(true);
    m_draftSaveTimer->setInterval(400);

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
    layout->addWidget(m_composerBudget);
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
    connect(m_send, &QPushButton::clicked,
            this, &V2WindowsMessagingPanel::sendComposition);
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
    connect(m_draftSaveTimer, &QTimer::timeout,
            this, &V2WindowsMessagingPanel::flushDraft);
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
    reconcileComposer();
}

V2WindowsMessagingPanel::~V2WindowsMessagingPanel() {
    flushDraft();
}

bool V2WindowsMessagingPanel::eventFilter(QObject *watched, QEvent *event) {
    if (watched != m_composer || event->type() != QEvent::KeyPress)
        return QWidget::eventFilter(watched, event);
    const auto *key = static_cast<QKeyEvent *>(event);
    const bool enter = key->key() == Qt::Key_Return || key->key() == Qt::Key_Enter;
    if (enter && key->modifiers() == Qt::ControlModifier) {
        if (m_send->isEnabled()) sendComposition();
        return true;
    }
    if (key->key() == Qt::Key_Escape
            && (!m_editTargetMessageId.isEmpty()
                || !m_viewModel->replyTargetMessageId().isEmpty())) {
        cancelComposition();
        return true;
    }
    return QWidget::eventFilter(watched, event);
}

void V2WindowsMessagingPanel::setConversation(const QString &conversationId) {
    if (conversationId == m_conversationId) return;
    flushDraft();
    m_conversationId = conversationId;
    m_editTargetMessageId.clear();
    m_participantPane->hide();
    m_mentionAnchors.clear();
    m_updatingComposer = true;
    m_composer->setPlainText(m_viewModel->draft());
    m_composer->moveCursor(QTextCursor::End);
    m_updatingComposer = false;
    m_previousComposerText = m_composer->toPlainText();
    m_pendingSearchRevealMessageId.clear();
    m_mention->setEnabled(false);
    if (m_searchViewModel) {
        m_searchViewModel->activate(conversationId);
        m_searchInput->clear();
        renderSearch();
    }
    render();
}

void V2WindowsMessagingPanel::render() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
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
        body->setAccessibleName(copy.messageBodyAccessible.arg(message.text));
        body->setProperty("mentionTargetAccountIds", mentionTargetAccountIds);
        body->setWordWrap(true);
        body->setTextInteractionFlags(Qt::TextSelectableByKeyboard | Qt::TextSelectableByMouse);
        if (!message.replyPreview.isEmpty()) {
            auto *reference = new QLabel(copy.quotedMessage.arg(message.replyPreview), row);
            reference->setAccessibleName(copy.quotedMessageAccessible);
            reference->setWordWrap(true);
            layout->addWidget(reference);
        }
        layout->addWidget(body);
        if (message.forwarded) {
            auto *forwarded = new QLabel(copy.forwarded, row);
            forwarded->setAccessibleName(copy.forwardedAccessible);
            layout->addWidget(forwarded);
        }
        if (message.edited) {
            auto *edited = new QLabel(copy.edited, row);
            edited->setAccessibleName(copy.editedAccessible);
            layout->addWidget(edited);
        }
        if (message.editPending) {
            auto *status = new QLabel(copy.editSaving, row);
            status->setAccessibleName(copy.editSavingAccessible);
            layout->addWidget(status);
        } else if (message.editConflict || message.editFailed) {
            auto *status = new QLabel(message.editConflict
                ? copy.editConflictDraftRetained : copy.editFailedDraftRetained, row);
            status->setAccessibleName(message.editConflict
                ? copy.editConflictAccessible : copy.editFailedAccessible);
            status->setWordWrap(true);
            layout->addWidget(status);
            auto *editActions = new QHBoxLayout;
            auto *retry = new QPushButton(message.editConflict
                ? copy.retryFromNewVersion : copy.retryEdit, row);
            retry->setAccessibleName(message.editConflict
                ? copy.retryFromNewVersionAccessible : copy.retryEditAccessible);
            connect(retry, &QPushButton::clicked, m_viewModel,
                [model = m_viewModel, id = message.editOperationId,
                 conflict = message.editConflict] {
                    if (conflict) model->rebaseEdit(id);
                    else model->retryEdit(id);
                });
            auto *discard = new QPushButton(copy.discardDraft, row);
            discard->setAccessibleName(copy.discardDraftAccessible);
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
            delivery->setAccessibleName(copy.deliveryStatusAccessible);
            actions->addWidget(delivery);
        }
        actions->addStretch();
        if (message.canRetry) {
            auto *retry = new QPushButton(copy.retry, row);
            retry->setAccessibleName(copy.retrySendAccessible);
            connect(retry, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.clientMessageId] { model->retry(id); });
            actions->addWidget(retry);
        }
        if (message.canReply) {
            auto *copyButton = new QPushButton(copy.copy, row);
            copyButton->setAccessibleName(copy.copyMessageAccessible);
            connect(copyButton, &QPushButton::clicked, this,
                [text = message.text] {
                    if (auto *clipboard = QGuiApplication::clipboard())
                        clipboard->setText(text);
                });
            actions->addWidget(copyButton);
            if (m_forwardingEnabled && message.canForward) {
                auto *forward = new QPushButton(copy.forward, row);
                forward->setAccessibleName(copy.forwardMessageAccessible);
                connect(forward, &QPushButton::clicked, this,
                    [this, id = message.messageId] { chooseForward(id); });
                actions->addWidget(forward);
            }
            auto *reply = new QPushButton(copy.reply, row);
            reply->setAccessibleName(copy.replyMessageAccessible);
            connect(reply, &QPushButton::clicked, this,
                    [this, id = message.messageId] { chooseReply(id); });
            actions->addWidget(reply);
            if (message.canEdit) {
                auto *edit = new QPushButton(copy.edit, row);
                edit->setAccessibleName(copy.editMessageAccessible);
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
                ? copy.pinRetry : (message.pinned ? copy.unpin : copy.pin));
            pin->setAccessibleName(message.pinFailed
                ? copy.pinRetryAccessible
                : (message.pinned ? copy.unpinAccessible : copy.pinAccessible));
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
                button->setAccessibleName(copy.reactionAccessible
                    .arg(labels.value(index)).arg(reaction.count));
                if (reaction.failed) {
                    button->setText(copy.reactionRetry.arg(labels.value(index)));
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
        ? copy.editingMessage : m_viewModel->replyBanner());
    m_replyBanner->setVisible(composing);
    m_cancelReply->setText(editing ? copy.cancelEdit : copy.cancelReply);
    m_cancelReply->setAccessibleName(editing
        ? copy.cancelEditAccessible : copy.cancelReplyAccessible);
    m_cancelReply->setVisible(composing);
    m_send->setText(editing ? copy.saveEdit
        : replying ? copy.sendReply : copy.sendMessage);
    m_send->setAccessibleName(editing
        ? copy.saveEditAccessible
        : replying ? copy.sendReplyAccessible : copy.sendMessageAccessible);
    m_mention->setEnabled(
        m_mentionsEnabled && !m_conversationId.isEmpty());
    m_send->setEnabled(!m_conversationId.isEmpty()
        && !m_composer->toPlainText().trimmed().isEmpty());
    if (!m_pendingSearchRevealMessageId.isEmpty()) {
        const QString identity = m_pendingSearchRevealMessageId;
        QTimer::singleShot(0, this, [this, identity] {
            if (m_pendingSearchRevealMessageId != identity
                    || !revealMessage(identity))
                return;
            m_pendingSearchRevealMessageId.clear();
            m_searchStatus->setText(
                WindowsLocaleCatalog::messages(m_locale).searchLocated);
            QAccessibleEvent announcement(m_searchStatus, QAccessible::Alert);
            QAccessible::updateAccessibility(&announcement);
        });
    }
}

void V2WindowsMessagingPanel::startSearch() {
    if (!m_searchViewModel || m_conversationId.isEmpty()) return;
    if (!m_searchViewModel->search(m_searchInput->text())) {
        m_searchStatus->setText(
            WindowsLocaleCatalog::messages(m_locale).searchInvalid);
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
            WindowsLocaleCatalog::messages(m_locale).searchResultActivateAccessible);
        item->setToolTip(row.text);
    }
    m_searchButton->setEnabled(!m_searchViewModel->busy());
    m_searchInput->setEnabled(!m_searchViewModel->busy());
    m_searchLoadMore->setEnabled(
        !m_searchViewModel->busy() && m_searchViewModel->hasMore());
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    if (!m_searchViewModel->failure().isEmpty()) {
        if (!m_searchViewModel->contextBusy())
            m_pendingSearchRevealMessageId.clear();
        m_searchStatus->setText(m_searchViewModel->failure());
    } else if (m_searchViewModel->contextBusy())
        m_searchStatus->setText(copy.searchLoadingContext);
    else if (m_searchViewModel->busy())
        m_searchStatus->setText(copy.searching);
    else if (!m_searchViewModel->query().isEmpty())
        m_searchStatus->setText(copy.searchFound.arg(m_searchViewModel->rows().size()));
    else
        m_searchStatus->setText(copy.searchPageOnly);
    if (m_searchPane->isVisible()) {
        QAccessibleEvent announcement(m_searchStatus, QAccessible::Alert);
        QAccessible::updateAccessibility(&announcement);
    }
}

void V2WindowsMessagingPanel::revealSearchResult(QListWidgetItem *item) {
    if (!item) return;
    const QString messageId = item->data(Qt::UserRole).toString();
    if (revealMessage(messageId)) return;
    m_pendingSearchRevealMessageId = messageId;
    if (!m_searchViewModel || !m_searchViewModel->requestContext(messageId)) {
        m_pendingSearchRevealMessageId.clear();
        m_searchStatus->setText(
            WindowsLocaleCatalog::messages(m_locale).searchContextUnavailable);
    }
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
        m_directoryViewModel->rows(), m_conversationId, this, true, m_locale);
    if (dialog.exec() != QDialog::Accepted) return;
    m_viewModel->forwardMessage(messageId, dialog.selectedConversationId());
}

void V2WindowsMessagingPanel::renderParticipants() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
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
        m_participantStatus->setText(copy.participantsLoading);
    else if (m_participantViewModel->rows().isEmpty())
        m_participantStatus->setText(copy.participantsEmpty);
    else
        m_participantStatus->setText(copy.participantInstruction);
}

void V2WindowsMessagingPanel::chooseReply(const QString &messageId) {
    if (messageId.isEmpty()) return;
    if (!m_editTargetMessageId.isEmpty()) {
        m_editTargetMessageId.clear();
        restoreDraft();
    }
    m_viewModel->chooseReply(messageId);
}

void V2WindowsMessagingPanel::beginEdit(
        const QString &messageId, const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    try {
        const auto anchors = V2WindowsMentionComposer::restore(text, mentions);
        flushDraft();
        m_draftBeforeEdit = m_composer->toPlainText();
        m_draftAnchorsBeforeEdit = m_mentionAnchors;
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
        m_status->setText(
            WindowsLocaleCatalog::messages(m_locale).mentionsRestoreFailed);
    }
}

void V2WindowsMessagingPanel::cancelComposition() {
    const bool editing = !m_editTargetMessageId.isEmpty();
    m_editTargetMessageId.clear();
    m_participantPane->hide();
    if (editing) {
        restoreDraft();
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
        m_status->setText(
            WindowsLocaleCatalog::messages(m_locale).mentionInsertFailed);
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
    const qsizetype bytes = next.toUtf8().size();
    const bool withinBudget = bytes <= V2LocalMessageRepository::MaxTextBytes;
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_composerBudget->setText(withinBudget
        ? copy.bytesUsed.arg(bytes).arg(V2LocalMessageRepository::MaxTextBytes)
        : copy.bytesOverLimit
            .arg(bytes - V2LocalMessageRepository::MaxTextBytes)
            .arg(V2LocalMessageRepository::MaxTextBytes));
    m_send->setEnabled(!m_conversationId.isEmpty()
        && !next.trimmed().isEmpty() && withinBudget);
    if (!m_updatingComposer && m_editTargetMessageId.isEmpty()
            && !m_conversationId.isEmpty())
        m_draftSaveTimer->start();
}

void V2WindowsMessagingPanel::flushDraft() {
    if (m_draftSaveTimer) m_draftSaveTimer->stop();
    if (m_updatingComposer || !m_editTargetMessageId.isEmpty()
            || m_conversationId.isEmpty())
        return;
    m_viewModel->persistDraft(
        m_conversationId,
        m_composer->toPlainText().left(V2LocalMessageRepository::MaxDraftLength));
}

void V2WindowsMessagingPanel::restoreDraft() {
    m_updatingComposer = true;
    m_composer->setPlainText(m_draftBeforeEdit);
    m_composer->moveCursor(QTextCursor::End);
    m_updatingComposer = false;
    m_previousComposerText = m_draftBeforeEdit;
    m_mentionAnchors = m_draftAnchorsBeforeEdit;
    m_draftBeforeEdit.clear();
    m_draftAnchorsBeforeEdit.clear();
}

void V2WindowsMessagingPanel::sendComposition() {
    QList<V2LocalMessageRepository::Mention> mentions;
    try {
        mentions = V2WindowsMentionComposer::serialize(
            m_composer->toPlainText(), m_mentionAnchors);
    } catch (const std::exception &) {
        m_status->setText(
            WindowsLocaleCatalog::messages(m_locale).mentionInvalid);
        return;
    }
    const bool accepted = !m_editTargetMessageId.isEmpty()
        ? m_viewModel->editMessage(
            m_editTargetMessageId, m_composer->toPlainText(), mentions)
        : !m_viewModel->replyTargetMessageId().isEmpty()
            ? m_viewModel->sendReply(m_composer->toPlainText(), mentions)
            : m_viewModel->sendText(m_composer->toPlainText(), mentions);
    if (accepted) {
        const bool editing = !m_editTargetMessageId.isEmpty();
        m_editTargetMessageId.clear();
        if (editing) {
            restoreDraft();
        } else {
            m_draftSaveTimer->stop();
            m_updatingComposer = true;
            m_composer->clear();
            m_updatingComposer = false;
            m_mentionAnchors.clear();
            m_previousComposerText.clear();
        }
        m_composer->setFocus();
    }
}
