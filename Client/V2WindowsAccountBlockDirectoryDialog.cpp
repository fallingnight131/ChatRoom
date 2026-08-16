#include "V2WindowsAccountBlockDirectoryDialog.h"

#include "V2WindowsAccountBlockDirectoryViewModel.h"

#include <QDateTime>
#include <QDialogButtonBox>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QVBoxLayout>
#include <stdexcept>

V2WindowsAccountBlockDirectoryDialog::V2WindowsAccountBlockDirectoryDialog(
        V2WindowsAccountBlockDirectoryViewModel *viewModel, QWidget *parent)
    : QDialog(parent), m_viewModel(viewModel), m_list(new QListWidget(this)),
      m_status(new QLabel(this)),
      m_refresh(new QPushButton(QStringLiteral("刷新"), this)),
      m_loadMore(new QPushButton(QStringLiteral("加载更多"), this)) {
    if (!m_viewModel)
        throw std::invalid_argument("account block directory dialog requires a view model");
    setWindowTitle(QStringLiteral("隐私与屏蔽账号"));
    setAccessibleName(QStringLiteral("隐私与屏蔽账号管理窗口"));
    resize(520, 420);
    m_list->setAccessibleName(QStringLiteral("已屏蔽账号列表"));
    m_status->setAccessibleName(QStringLiteral("屏蔽账号目录状态"));
    m_status->setWordWrap(true);
    m_refresh->setAccessibleName(QStringLiteral("刷新已屏蔽账号列表"));
    m_loadMore->setAccessibleName(QStringLiteral("加载更多已屏蔽账号"));

    auto *buttons = new QDialogButtonBox(this);
    buttons->addButton(m_refresh, QDialogButtonBox::ActionRole);
    buttons->addButton(m_loadMore, QDialogButtonBox::ActionRole);
    buttons->addButton(QDialogButtonBox::Close);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(new QLabel(
        QStringLiteral("这里显示由服务器保存的屏蔽关系。消息历史不会被删除。"), this));
    layout->addWidget(m_list, 1);
    layout->addWidget(m_status);
    layout->addWidget(buttons);

    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked,
            m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::refresh);
    connect(m_loadMore, &QPushButton::clicked,
            m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::loadMore);
    connect(m_viewModel, &V2WindowsAccountBlockDirectoryViewModel::changed,
            this, &V2WindowsAccountBlockDirectoryDialog::render);
    render();
}

void V2WindowsAccountBlockDirectoryDialog::render() {
    m_list->clear();
    for (const auto &row : m_viewModel->rows()) {
        const QString name = row.targetDisplayName.isEmpty()
            ? QStringLiteral("该账号") : row.targetDisplayName;
        auto *item = new QListWidgetItem(
            QStringLiteral("%1\n%2").arg(name, row.targetAccountId), m_list);
        item->setData(Qt::UserRole, row.targetAccountId);
        item->setData(Qt::AccessibleTextRole,
                      QStringLiteral("已屏蔽账号 %1").arg(name));
        item->setToolTip(QStringLiteral("屏蔽时间：%1").arg(
            QDateTime::fromMSecsSinceEpoch(row.blockedAtEpochMs).toString(Qt::ISODate)));
    }
    if (m_viewModel->busy()) {
        m_status->setText(QStringLiteral("正在读取屏蔽账号…"));
    } else if (!m_viewModel->failure().isEmpty()) {
        m_status->setText(m_viewModel->failure());
    } else if (m_viewModel->rows().isEmpty()) {
        m_status->setText(m_viewModel->available()
            ? QStringLiteral("当前没有已屏蔽账号")
            : QStringLiteral("屏蔽服务尚未连接"));
    } else {
        m_status->setText(QStringLiteral("已加载 %1 个屏蔽账号").arg(
            m_viewModel->rows().size()));
    }
    m_refresh->setEnabled(m_viewModel->available() && !m_viewModel->busy());
    m_loadMore->setEnabled(m_viewModel->available() && !m_viewModel->busy()
                           && m_viewModel->hasMore());
}
