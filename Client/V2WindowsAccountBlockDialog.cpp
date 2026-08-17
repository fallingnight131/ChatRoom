#include "V2WindowsAccountBlockDialog.h"

#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"

#include <QDialogButtonBox>
#include <QLabel>
#include <QMessageBox>
#include <QPushButton>
#include <QVBoxLayout>
#include <stdexcept>
#include <utility>

V2WindowsAccountBlockDialog::V2WindowsAccountBlockDialog(
        V2WindowsAccountBlockViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        Confirm confirm, QWidget *parent, WindowsLocale locale)
    : QDialog(parent), m_viewModel(viewModel),
      m_participantViewModel(participantViewModel), m_confirm(std::move(confirm)),
      m_target(new QLabel(this)), m_status(new QLabel(this)),
      m_block(new QPushButton(WindowsLocaleCatalog::messages(locale).blockAccount, this)),
      m_unblock(new QPushButton(WindowsLocaleCatalog::messages(locale).unblockAccount, this)),
      m_locale(locale) {
    if (!m_viewModel || !m_participantViewModel)
        throw std::invalid_argument("account block dialog requires view models");
    if (!m_confirm) {
        m_confirm = [locale](QWidget *parent, const QString &target, bool blocked) {
            const auto &copy = WindowsLocaleCatalog::messages(locale);
            const QString action = blocked ? copy.blockAction : copy.unblockAction;
            return QMessageBox::question(parent, copy.blockConfirmTitle.arg(action),
                copy.blockConfirmPrompt.arg(action, target),
                QMessageBox::Yes | QMessageBox::No, QMessageBox::No) == QMessageBox::Yes;
        };
    }
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.accountBlockTitle);
    setAccessibleName(copy.accountBlockWindowAccessible);
    setModal(true);
    m_target->setAccessibleName(copy.accountBlockTargetAccessible);
    m_target->setWordWrap(true);
    m_status->setAccessibleName(copy.accountBlockStatusAccessible);
    m_status->setWordWrap(true);
    m_block->setAccessibleName(copy.blockAccountAccessible);
    m_unblock->setAccessibleName(copy.unblockAccountAccessible);

    auto *actions = new QDialogButtonBox(this);
    actions->addButton(m_block, QDialogButtonBox::ActionRole);
    actions->addButton(m_unblock, QDialogButtonBox::ActionRole);
    actions->addButton(QDialogButtonBox::Close)->setText(copy.close);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(m_target);
    layout->addWidget(m_status);
    layout->addWidget(actions);

    connect(actions, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_block, &QPushButton::clicked, this, [this] { submit(true); });
    connect(m_unblock, &QPushButton::clicked, this, [this] { submit(false); });
    connect(m_viewModel, &V2WindowsAccountBlockViewModel::changed,
            this, &V2WindowsAccountBlockDialog::render);
    connect(m_participantViewModel,
            &V2WindowsConversationParticipantViewModel::changed,
            this, &V2WindowsAccountBlockDialog::synchronizeTarget);
    render();
}

void V2WindowsAccountBlockDialog::setConversation(
        const QString &conversationId, bool direct) {
    m_conversationId = conversationId;
    m_direct = direct;
    if (direct && !conversationId.isEmpty()
            && m_participantViewModel->conversationId() != conversationId) {
        m_participantViewModel->activate(conversationId);
    } else {
        synchronizeTarget();
    }
}

void V2WindowsAccountBlockDialog::synchronizeTarget() {
    m_viewModel->activateDirectConversation(
        m_conversationId, m_participantViewModel->conversationId(),
        m_participantViewModel->rows(), m_participantViewModel->hasMore(), m_direct);
}

void V2WindowsAccountBlockDialog::render() {
    const QString target = m_viewModel->targetDisplayName();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_target->setText(target.isEmpty()
        ? copy.accountBlockTargetMissing
        : copy.accountBlockCurrentTarget.arg(target));
    m_status->setText(m_viewModel->statusText());
    const bool enabled = m_viewModel->canSubmit();
    m_block->setEnabled(enabled);
    m_unblock->setEnabled(enabled);
}

void V2WindowsAccountBlockDialog::submit(bool blocked) {
    if (!m_viewModel->canSubmit()) return;
    const QString target = m_viewModel->targetDisplayName();
    if (!m_confirm(this, target, blocked)) return;
    m_viewModel->request(blocked);
}
