#include "V2WindowsConversationDialog.h"

#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsAccountBlockDialog.h"
#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"
#include "WindowsLocaleViewModel.h"

#include <QAccessible>
#include <QComboBox>
#include <QDialogButtonBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSignalBlocker>
#include <QSplitter>
#include <QShortcut>
#include <QStringList>
#include <QVBoxLayout>

namespace {
WindowsLocale resolvedLocale(
        WindowsLocale fallback, const WindowsLocaleViewModel *viewModel) {
    return viewModel ? viewModel->locale() : fallback;
}
}

V2WindowsConversationDialog::V2WindowsConversationDialog(
        V2WindowsConversationDirectoryViewModel *directoryViewModel,
        V2WindowsMessagingViewModel *messagingViewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent, bool mentionsEnabled, bool forwardingEnabled,
        V2WindowsMessageSearchViewModel *searchViewModel,
        V2WindowsAccountBlockViewModel *accountBlockViewModel,
        WindowsLocale locale, WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_directoryViewModel(directoryViewModel),
      m_participantViewModel(participantViewModel),
      m_messagingViewModel(messagingViewModel), m_searchViewModel(searchViewModel),
      m_accountBlockViewModel(accountBlockViewModel),
      m_localeViewModel(localeViewModel), m_localeLabel(new QLabel(this)),
      m_localeSelector(new QComboBox(this)), m_localeStatus(new QLabel(this)),
      m_directoryTitle(new QLabel(this)),
      m_directoryStatus(new QLabel(this)), m_conversationTitle(new QLabel(this)),
      m_conversations(new QListWidget(this)),
      m_refresh(new QPushButton(
          WindowsLocaleCatalog::messages(resolvedLocale(locale, localeViewModel)).refresh,
          this)),
      m_loadMore(new QPushButton(
          WindowsLocaleCatalog::messages(resolvedLocale(locale, localeViewModel)).loadMore,
          this)),
      m_accountBlock(new QPushButton(
          WindowsLocaleCatalog::messages(resolvedLocale(locale, localeViewModel)).accountBlock,
          this)),
      m_messagingPanel(new V2WindowsMessagingPanel(
          messagingViewModel, participantViewModel, this, mentionsEnabled,
          directoryViewModel, forwardingEnabled, searchViewModel,
          resolvedLocale(locale, localeViewModel))),
      m_splitter(new QSplitter(Qt::Horizontal, this)),
      m_closeButtons(new QDialogButtonBox(QDialogButtonBox::Close, this)),
      m_locale(resolvedLocale(locale, localeViewModel)) {
    Q_ASSERT(m_directoryViewModel);
    Q_ASSERT(messagingViewModel);
    Q_ASSERT(participantViewModel);
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.previewWindowTitle);
    setAccessibleName(copy.previewWindowAccessible);
    setMinimumSize(900, 600);

    m_directoryTitle->setText(copy.conversationDirectory);
    m_directoryTitle->setAccessibleName(copy.conversationDirectoryAccessible);
    m_directoryStatus->setAccessibleName(copy.conversationDirectoryStatusAccessible);
    m_directoryStatus->setWordWrap(true);
    m_conversations->setAccessibleName(copy.conversationListAccessible);
    m_conversations->setSelectionMode(QAbstractItemView::SingleSelection);
    m_refresh->setAccessibleName(copy.refreshConversationsAccessible);
    m_refresh->setToolTip(QStringLiteral("F5"));
    m_loadMore->setAccessibleName(copy.loadMoreConversationsAccessible);
    m_conversationTitle->setAccessibleName(copy.currentConversationAccessible);
    m_conversationTitle->setText(copy.selectConversation);
    m_accountBlock->setAccessibleName(copy.accountBlockAccessible);
    m_accountBlock->setVisible(m_accountBlockViewModel != nullptr);
    m_accountBlock->setEnabled(false);
    m_messagingPanel->setEnabled(false);

    auto *directoryButtons = new QHBoxLayout;
    directoryButtons->addWidget(m_refresh);
    directoryButtons->addWidget(m_loadMore);
    auto *directoryPane = new QWidget(this);
    auto *directoryLayout = new QVBoxLayout(directoryPane);
    directoryLayout->addWidget(m_directoryTitle);
    directoryLayout->addWidget(m_directoryStatus);
    directoryLayout->addWidget(m_conversations, 1);
    directoryLayout->addLayout(directoryButtons);

    auto *messagePane = new QWidget(this);
    auto *messageLayout = new QVBoxLayout(messagePane);
    auto *messageHeader = new QHBoxLayout;
    messageHeader->addWidget(m_conversationTitle, 1);
    messageHeader->addWidget(m_accountBlock);
    messageLayout->addLayout(messageHeader);
    messageLayout->addWidget(m_messagingPanel, 1);

    m_splitter->setAccessibleName(copy.conversationSplitterAccessible);
    m_splitter->addWidget(directoryPane);
    m_splitter->addWidget(messagePane);
    m_splitter->setStretchFactor(0, 1);
    m_splitter->setStretchFactor(1, 3);

    m_closeButtons->button(QDialogButtonBox::Close)->setText(copy.close);
    m_localeSelector->addItem(copy.chinese, static_cast<int>(WindowsLocale::ZhCn));
    m_localeSelector->addItem(copy.english, static_cast<int>(WindowsLocale::EnUs));
    m_localeSelector->setAccessibleName(copy.languageSelectorAccessible);
    m_localeSelector->setAccessibleDescription(copy.languageSelectorDescription);
    m_localeLabel->setBuddy(m_localeSelector);
    m_localeStatus->setAccessibleName(copy.localePreferenceStatusAccessible);
    m_localeStatus->setWordWrap(true);
    auto *localeRow = new QHBoxLayout;
    localeRow->addWidget(m_localeLabel);
    localeRow->addWidget(m_localeSelector);
    localeRow->addWidget(m_localeStatus, 1);
    m_localeLabel->setVisible(m_localeViewModel != nullptr);
    m_localeSelector->setVisible(m_localeViewModel != nullptr);
    m_localeStatus->setVisible(m_localeViewModel != nullptr);
    if (m_localeViewModel) {
        setTabOrder(m_localeSelector, m_refresh);
        setTabOrder(m_refresh, m_conversations);
        setTabOrder(m_conversations, m_loadMore);
    }
    auto *layout = new QVBoxLayout(this);
    layout->addLayout(localeRow);
    layout->addWidget(m_splitter, 1);
    layout->addWidget(m_closeButtons);

    connect(m_closeButtons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked,
            m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::refresh);
    connect(m_loadMore, &QPushButton::clicked,
            m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::loadMore);
    connect(m_conversations, &QListWidget::itemActivated,
            this, &V2WindowsConversationDialog::openItem);
    connect(m_conversations, &QListWidget::currentItemChanged,
            this, [this](QListWidgetItem *current) { openItem(current); });
    auto *refreshShortcut = new QShortcut(QKeySequence(Qt::Key_F5), this);
    connect(refreshShortcut, &QShortcut::activated, m_refresh, &QPushButton::click);
    auto *searchShortcut = new QShortcut(
        QKeySequence(Qt::CTRL | Qt::Key_F), this);
    connect(searchShortcut, &QShortcut::activated, this, [this] {
        m_messagingPanel->focusSearch();
    });
    connect(m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::changed,
            this, &V2WindowsConversationDialog::renderDirectory);
    connect(m_directoryViewModel,
            &V2WindowsConversationDirectoryViewModel::conversationOpened,
            this, &V2WindowsConversationDialog::markConversationOpened);
    connect(m_accountBlock, &QPushButton::clicked, this, [this] {
        if (!m_accountBlockViewModel || m_selectedConversationId.isEmpty()
                || !m_selectedConversationDirect) return;
        V2WindowsAccountBlockDialog dialog(
            m_accountBlockViewModel, m_participantViewModel, {}, this, m_locale);
        dialog.setConversation(m_selectedConversationId, true);
        dialog.exec();
    });
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &V2WindowsConversationDialog::applyLocale);
        connect(m_localeSelector, qOverload<int>(&QComboBox::currentIndexChanged),
                this, [this](int index) {
                    const auto locale = static_cast<WindowsLocale>(
                        m_localeSelector->itemData(index).toInt());
                    m_localeViewModel->select(locale);
                });
    }
    applyLocale();
    renderDirectory();
}

void V2WindowsConversationDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    m_directoryViewModel->setLocale(m_locale);
    m_participantViewModel->setLocale(m_locale);
    m_messagingViewModel->setLocale(m_locale);
    if (m_searchViewModel) m_searchViewModel->setLocale(m_locale);
    if (m_accountBlockViewModel) m_accountBlockViewModel->setLocale(m_locale);
    m_messagingPanel->setLocale(m_locale);

    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.previewWindowTitle);
    setAccessibleName(copy.previewWindowAccessible);
    m_localeLabel->setText(copy.language);
    m_localeSelector->setAccessibleName(copy.languageSelectorAccessible);
    m_localeSelector->setAccessibleDescription(copy.languageSelectorDescription);
    m_localeStatus->setAccessibleName(copy.localePreferenceStatusAccessible);
    m_localeStatus->setText(m_localeViewModel ? m_localeViewModel->failure() : QString());
    if (m_localeViewModel && !m_localeViewModel->failure().isEmpty()
            && isVisible()) {
        QAccessibleEvent announcement(m_localeStatus, QAccessible::Alert);
        QAccessible::updateAccessibility(&announcement);
    }
    {
        const QSignalBlocker blocker(m_localeSelector);
        m_localeSelector->setItemText(0, copy.chinese);
        m_localeSelector->setItemText(1, copy.english);
        m_localeSelector->setCurrentIndex(
            m_locale == WindowsLocale::EnUs ? 1 : 0);
    }
    m_directoryTitle->setText(copy.conversationDirectory);
    m_directoryTitle->setAccessibleName(copy.conversationDirectoryAccessible);
    m_directoryStatus->setAccessibleName(copy.conversationDirectoryStatusAccessible);
    m_conversations->setAccessibleName(copy.conversationListAccessible);
    m_refresh->setText(copy.refresh);
    m_refresh->setAccessibleName(copy.refreshConversationsAccessible);
    m_loadMore->setText(copy.loadMore);
    m_loadMore->setAccessibleName(copy.loadMoreConversationsAccessible);
    m_conversationTitle->setAccessibleName(copy.currentConversationAccessible);
    if (m_selectedConversationId.isEmpty())
        m_conversationTitle->setText(copy.selectConversation);
    m_accountBlock->setText(copy.accountBlock);
    m_accountBlock->setAccessibleName(copy.accountBlockAccessible);
    m_splitter->setAccessibleName(copy.conversationSplitterAccessible);
    m_closeButtons->button(QDialogButtonBox::Close)->setText(copy.close);
    renderDirectory();
}

void V2WindowsConversationDialog::renderDirectory() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    const QSignalBlocker blocker(m_conversations);
    m_conversations->clear();
    QListWidgetItem *selected = nullptr;
    for (const auto &row : m_directoryViewModel->rows()) {
        QStringList details;
        if (!row.kindLabel.isEmpty()) details.append(row.kindLabel);
        if (!row.roleLabel.isEmpty()) details.append(row.roleLabel);
        if (row.unreadCount > 0)
            details.append(copy.unreadCount.arg(row.unreadCount));
        auto *item = new QListWidgetItem(
            QStringLiteral("%1\n%2").arg(row.displayName, details.join(QStringLiteral(" · "))),
            m_conversations);
        item->setData(Qt::UserRole, row.conversationId);
        item->setToolTip(row.displayName);
        if (row.conversationId == m_selectedConversationId) selected = item;
    }
    if (selected) m_conversations->setCurrentItem(selected);

    m_refresh->setEnabled(!m_directoryViewModel->busy());
    m_loadMore->setEnabled(
        !m_directoryViewModel->busy() && m_directoryViewModel->hasMore());
    if (!m_directoryViewModel->failure().isEmpty())
        m_directoryStatus->setText(m_directoryViewModel->failure());
    else if (m_directoryViewModel->busy())
        m_directoryStatus->setText(copy.loadingConversations);
    else if (m_directoryViewModel->rows().isEmpty())
        m_directoryStatus->setText(copy.noConversations);
    else
        m_directoryStatus->clear();
}

void V2WindowsConversationDialog::openItem(QListWidgetItem *item) {
    if (!item) return;
    const QString conversationId = item->data(Qt::UserRole).toString();
    if (conversationId.isEmpty() || conversationId == m_selectedConversationId) return;
    m_directoryViewModel->openConversation(conversationId);
}

void V2WindowsConversationDialog::markConversationOpened(
        const QString &conversationId) {
    m_selectedConversationId = conversationId;
    for (const auto &row : m_directoryViewModel->rows()) {
        if (row.conversationId != conversationId) continue;
        m_conversationTitle->setText(row.displayName);
        m_selectedConversationDirect = row.direct;
        m_accountBlock->setEnabled(m_accountBlockViewModel && row.direct);
        m_messagingPanel->setConversation(conversationId);
        m_messagingPanel->setEnabled(true);
        break;
    }
}
