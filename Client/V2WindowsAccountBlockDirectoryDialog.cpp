#include "V2WindowsAccountBlockDirectoryDialog.h"

#include "V2WindowsAccountBlockDirectoryViewModel.h"
#include "WindowsLocaleViewModel.h"

#include <QDateTime>
#include <QDialogButtonBox>
#include <QLabel>
#include <QListWidget>
#include <QMessageBox>
#include <QPushButton>
#include <QSignalBlocker>
#include <QVBoxLayout>
#include <stdexcept>

V2WindowsAccountBlockDirectoryDialog::V2WindowsAccountBlockDirectoryDialog(
        V2WindowsAccountBlockDirectoryViewModel *viewModel,
        Confirm confirm, QWidget *parent, WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_viewModel(viewModel), m_list(new QListWidget(this)),
      m_status(new QLabel(this)), m_intro(new QLabel(this)),
      m_refresh(new QPushButton(this)), m_loadMore(new QPushButton(this)),
      m_unblock(new QPushButton(this)), m_close(new QPushButton(this)),
      m_confirm(std::move(confirm)), m_localeViewModel(localeViewModel) {
    if (!m_viewModel)
        throw std::invalid_argument("account block directory dialog requires a view model");
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    if (!m_confirm)
        m_confirm = [this](QWidget *, const QString &name) { return confirmUnblock(name); };
    resize(520, 420);
    m_status->setWordWrap(true);
    m_intro->setWordWrap(true);

    auto *buttons = new QDialogButtonBox(this);
    buttons->addButton(m_refresh, QDialogButtonBox::ActionRole);
    buttons->addButton(m_loadMore, QDialogButtonBox::ActionRole);
    buttons->addButton(m_unblock, QDialogButtonBox::ActionRole);
    buttons->addButton(m_close, QDialogButtonBox::RejectRole);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(m_intro);
    layout->addWidget(m_list, 1);
    layout->addWidget(m_status);
    layout->addWidget(buttons);

    connect(m_close, &QPushButton::clicked, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked,
            m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::refresh);
    connect(m_loadMore, &QPushButton::clicked,
            m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::loadMore);
    connect(m_unblock, &QPushButton::clicked,
            this, &V2WindowsAccountBlockDirectoryDialog::submitUnblock);
    connect(m_list, &QListWidget::itemSelectionChanged,
            this, &V2WindowsAccountBlockDirectoryDialog::render);
    connect(m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::changed,
            this, &V2WindowsAccountBlockDirectoryDialog::render);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &V2WindowsAccountBlockDirectoryDialog::applyLocale);
    }
    applyLocale();
}

void V2WindowsAccountBlockDirectoryDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.blockDirectoryTitle);
    setAccessibleName(copy.blockDirectoryWindowAccessible);
    m_intro->setText(copy.blockDirectoryIntro);
    m_list->setAccessibleName(copy.blockDirectoryListAccessible);
    m_status->setAccessibleName(copy.blockDirectoryStatusAccessible);
    m_refresh->setText(copy.refresh);
    m_refresh->setAccessibleName(copy.blockDirectoryRefreshAccessible);
    m_loadMore->setText(copy.loadMore);
    m_loadMore->setAccessibleName(copy.blockDirectoryLoadMoreAccessible);
    m_unblock->setText(copy.blockDirectoryUnblockSelected);
    m_unblock->setAccessibleName(copy.blockDirectoryUnblockAccessible);
    m_close->setText(copy.close);
    render();
}

QString V2WindowsAccountBlockDirectoryDialog::failureText() const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    switch (m_viewModel->failure()) {
    case V2WindowsAccountBlockDirectoryViewModel::Failure::None: return {};
    case V2WindowsAccountBlockDirectoryViewModel::Failure::SessionEnded:
        return copy.blockDirectorySessionEnded;
    case V2WindowsAccountBlockDirectoryViewModel::Failure::RefreshNotSent:
        return copy.blockDirectoryRefreshNotSent;
    case V2WindowsAccountBlockDirectoryViewModel::Failure::LoadMoreNotSent:
        return copy.blockDirectoryLoadMoreNotSent;
    case V2WindowsAccountBlockDirectoryViewModel::Failure::RetryableRequestFailed:
        return m_viewModel->failureDetail().isEmpty()
            ? copy.blockDirectoryRetryableRequestFailed
            : m_viewModel->failureDetail();
    case V2WindowsAccountBlockDirectoryViewModel::Failure::RequestFailed:
        return m_viewModel->failureDetail().isEmpty()
            ? copy.blockDirectoryRequestFailed : m_viewModel->failureDetail();
    case V2WindowsAccountBlockDirectoryViewModel::Failure::ServiceUnavailable:
        return copy.blockDirectoryServiceUnavailable;
    }
    return {};
}

QString V2WindowsAccountBlockDirectoryDialog::mutationFailureText() const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    switch (m_viewModel->mutationFailure()) {
    case V2WindowsAccountBlockDirectoryViewModel::MutationFailure::None: return {};
    case V2WindowsAccountBlockDirectoryViewModel::MutationFailure::NotSent:
        return copy.blockDirectoryMutationNotSent;
    case V2WindowsAccountBlockDirectoryViewModel::MutationFailure::Retryable:
        return copy.blockDirectoryMutationRetryable;
    case V2WindowsAccountBlockDirectoryViewModel::MutationFailure::Failed:
        return copy.blockDirectoryMutationFailed;
    case V2WindowsAccountBlockDirectoryViewModel::MutationFailure::Disconnected:
        return copy.blockDirectoryMutationDisconnected;
    }
    return {};
}

void V2WindowsAccountBlockDirectoryDialog::render() {
    const QString selectedId = m_list->currentItem()
        ? m_list->currentItem()->data(Qt::UserRole).toString() : QString{};
    const QSignalBlocker blocker(m_list);
    m_list->clear();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    for (const auto &row : m_viewModel->rows()) {
        const QString name = row.targetDisplayName.isEmpty()
            ? copy.blockDirectoryFallbackAccount : row.targetDisplayName;
        auto *item = new QListWidgetItem(
            QStringLiteral("%1\n%2").arg(name, row.targetAccountId), m_list);
        item->setData(Qt::UserRole, row.targetAccountId);
        item->setData(Qt::AccessibleTextRole,
                      copy.blockDirectoryRowAccessible.arg(name));
        item->setToolTip(copy.blockDirectoryBlockedAt.arg(
            QDateTime::fromMSecsSinceEpoch(row.blockedAtEpochMs).toString(Qt::ISODate)));
        if (row.targetAccountId == selectedId) m_list->setCurrentItem(item);
    }
    if (m_viewModel->mutationPending()) {
        m_status->setText(copy.blockDirectoryUnblocking);
    } else if (m_viewModel->mutationFailure()
               != V2WindowsAccountBlockDirectoryViewModel::MutationFailure::None) {
        m_status->setText(mutationFailureText());
    } else if (m_viewModel->busy()) {
        m_status->setText(copy.blockDirectoryReading);
    } else if (m_viewModel->failure()
               != V2WindowsAccountBlockDirectoryViewModel::Failure::None) {
        m_status->setText(failureText());
    } else if (m_viewModel->rows().isEmpty()) {
        m_status->setText(m_viewModel->available()
            ? copy.blockDirectoryEmpty : copy.blockDirectoryNotConnected);
    } else {
        m_status->setText(copy.blockDirectoryLoadedCount.arg(m_viewModel->rows().size()));
    }
    m_refresh->setEnabled(m_viewModel->available() && !m_viewModel->busy()
                          && !m_viewModel->mutationPending());
    m_loadMore->setEnabled(m_viewModel->available() && !m_viewModel->busy()
                           && m_viewModel->hasMore());
    const auto *selected = m_list->currentItem();
    m_unblock->setEnabled(selected && m_viewModel->canUnblock(
        selected->data(Qt::UserRole).toString()));
}

void V2WindowsAccountBlockDirectoryDialog::submitUnblock() {
    auto *selected = m_list->currentItem();
    if (!selected) return;
    const QString targetAccountId = selected->data(Qt::UserRole).toString();
    if (!m_viewModel->canUnblock(targetAccountId)) return;
    QString displayName = WindowsLocaleCatalog::messages(
        m_locale).blockDirectoryFallbackAccount;
    for (const auto &row : m_viewModel->rows()) {
        if (row.targetAccountId == targetAccountId) {
            if (!row.targetDisplayName.isEmpty()) displayName = row.targetDisplayName;
            break;
        }
    }
    if (m_confirm(this, displayName)) m_viewModel->requestUnblock(targetAccountId);
}

bool V2WindowsAccountBlockDirectoryDialog::confirmUnblock(
        const QString &displayName) {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    QMessageBox confirmation(
        QMessageBox::Question, copy.blockDirectoryConfirmTitle,
        copy.blockDirectoryConfirmPrompt.arg(displayName),
        QMessageBox::NoButton, this);
    auto *cancel = confirmation.addButton(copy.cancel, QMessageBox::RejectRole);
    auto *confirm = confirmation.addButton(copy.unblockAccount, QMessageBox::AcceptRole);
    confirmation.setDefaultButton(cancel);
    confirmation.exec();
    return confirmation.clickedButton() == confirm;
}
