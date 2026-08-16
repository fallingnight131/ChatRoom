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
        V2WindowsAccountBlockViewModel *accountBlockViewModel)
    : QDialog(parent), m_directoryViewModel(directoryViewModel),
      m_participantViewModel(participantViewModel),
      m_accountBlockViewModel(accountBlockViewModel),
      m_directoryStatus(new QLabel(this)), m_conversationTitle(new QLabel(this)),
      m_conversations(new QListWidget(this)),
      m_refresh(new QPushButton(QStringLiteral("刷新"), this)),
      m_loadMore(new QPushButton(QStringLiteral("加载更多"), this)),
      m_accountBlock(new QPushButton(QStringLiteral("屏蔽管理"), this)),
      m_messagingPanel(new V2WindowsMessagingPanel(
          messagingViewModel, participantViewModel, this, mentionsEnabled,
          directoryViewModel, forwardingEnabled, searchViewModel)) {
    Q_ASSERT(m_directoryViewModel);
    Q_ASSERT(messagingViewModel);
    Q_ASSERT(participantViewModel);
    setWindowTitle(QStringLiteral("新版会话与回复（预览）"));
    setAccessibleName(QStringLiteral("新版会话与回复预览窗口"));
    setMinimumSize(900, 600);

    auto *directoryTitle = new QLabel(QStringLiteral("会话"), this);
    directoryTitle->setAccessibleName(QStringLiteral("会话目录标题"));
    m_directoryStatus->setAccessibleName(QStringLiteral("会话目录状态"));
    m_directoryStatus->setWordWrap(true);
    m_conversations->setAccessibleName(QStringLiteral("新版会话列表"));
    m_conversations->setSelectionMode(QAbstractItemView::SingleSelection);
    m_refresh->setAccessibleName(QStringLiteral("刷新新版会话列表"));
    m_loadMore->setAccessibleName(QStringLiteral("加载更多新版会话"));
    m_conversationTitle->setAccessibleName(QStringLiteral("当前新版会话"));
    m_conversationTitle->setText(QStringLiteral("请选择会话"));
    m_accountBlock->setAccessibleName(QStringLiteral("管理当前私聊账号屏蔽状态"));
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
    splitter->setAccessibleName(QStringLiteral("会话与消息分栏"));
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
    const QSignalBlocker blocker(m_conversations);
    m_conversations->clear();
    QListWidgetItem *selected = nullptr;
    for (const auto &row : m_directoryViewModel->rows()) {
        QStringList details;
        if (!row.kindLabel.isEmpty()) details.append(row.kindLabel);
        if (!row.roleLabel.isEmpty()) details.append(row.roleLabel);
        if (row.unreadCount > 0)
            details.append(QStringLiteral("%1 条未读").arg(row.unreadCount));
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
        m_directoryStatus->setText(QStringLiteral("正在加载会话…"));
    else if (m_directoryViewModel->rows().isEmpty())
        m_directoryStatus->setText(QStringLiteral("暂无会话"));
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
