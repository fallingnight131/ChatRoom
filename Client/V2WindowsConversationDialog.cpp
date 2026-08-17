#include "V2WindowsConversationDialog.h"

#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsConversationParticipantViewModel.h"
#include "V2WindowsAccountBlockDialog.h"
#include "V2WindowsAccountBlockViewModel.h"
#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsMessageSearchViewModel.h"

#include <QDialogButtonBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSignalBlocker>
#include <QSplitter>
#include <QStringList>
#include <QVBoxLayout>

V2WindowsConversationDialog::V2WindowsConversationDialog(
        V2WindowsConversationDirectoryViewModel *directoryViewModel,
        V2WindowsMessagingViewModel *messagingViewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent, bool mentionsEnabled, bool forwardingEnabled,
        V2WindowsMessageSearchViewModel *searchViewModel,
        V2WindowsAccountBlockViewModel *accountBlockViewModel,
        WindowsLocale locale)
    : QDialog(parent), m_directoryViewModel(directoryViewModel),
      m_participantViewModel(participantViewModel),
      m_accountBlockViewModel(accountBlockViewModel),
      m_directoryStatus(new QLabel(this)), m_conversationTitle(new QLabel(this)),
      m_conversations(new QListWidget(this)),
      m_refresh(new QPushButton(WindowsLocaleCatalog::messages(locale).refresh, this)),
      m_loadMore(new QPushButton(WindowsLocaleCatalog::messages(locale).loadMore, this)),
      m_accountBlock(new QPushButton(WindowsLocaleCatalog::messages(locale).accountBlock, this)),
      m_messagingPanel(new V2WindowsMessagingPanel(
          messagingViewModel, participantViewModel, this, mentionsEnabled,
          directoryViewModel, forwardingEnabled, searchViewModel, locale)),
      m_locale(locale) {
    Q_ASSERT(m_directoryViewModel);
    Q_ASSERT(messagingViewModel);
    Q_ASSERT(participantViewModel);
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.previewWindowTitle);
    setAccessibleName(copy.previewWindowAccessible);
    setMinimumSize(900, 600);

    auto *directoryTitle = new QLabel(copy.conversationDirectory, this);
    directoryTitle->setAccessibleName(copy.conversationDirectoryAccessible);
    m_directoryStatus->setAccessibleName(copy.conversationDirectoryStatusAccessible);
    m_directoryStatus->setWordWrap(true);
    m_conversations->setAccessibleName(copy.conversationListAccessible);
    m_conversations->setSelectionMode(QAbstractItemView::SingleSelection);
    m_refresh->setAccessibleName(copy.refreshConversationsAccessible);
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
    directoryLayout->addWidget(directoryTitle);
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

    auto *splitter = new QSplitter(Qt::Horizontal, this);
    splitter->setAccessibleName(copy.conversationSplitterAccessible);
    splitter->addWidget(directoryPane);
    splitter->addWidget(messagePane);
    splitter->setStretchFactor(0, 1);
    splitter->setStretchFactor(1, 3);

    auto *buttons = new QDialogButtonBox(QDialogButtonBox::Close, this);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(splitter, 1);
    layout->addWidget(buttons);

    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked,
            m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::refresh);
    connect(m_loadMore, &QPushButton::clicked,
            m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::loadMore);
    connect(m_conversations, &QListWidget::itemActivated,
            this, &V2WindowsConversationDialog::openItem);
    connect(m_conversations, &QListWidget::currentItemChanged,
            this, [this](QListWidgetItem *current) { openItem(current); });
    connect(m_directoryViewModel, &V2WindowsConversationDirectoryViewModel::changed,
            this, &V2WindowsConversationDialog::renderDirectory);
    connect(m_directoryViewModel,
            &V2WindowsConversationDirectoryViewModel::conversationOpened,
            this, &V2WindowsConversationDialog::markConversationOpened);
    connect(m_accountBlock, &QPushButton::clicked, this, [this] {
        if (!m_accountBlockViewModel || m_selectedConversationId.isEmpty()
                || !m_selectedConversationDirect) return;
        V2WindowsAccountBlockDialog dialog(
            m_accountBlockViewModel, m_participantViewModel, {}, this);
        dialog.setConversation(m_selectedConversationId, true);
        dialog.exec();
    });
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
