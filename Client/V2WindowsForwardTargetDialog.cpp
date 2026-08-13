#include "V2WindowsForwardTargetDialog.h"

#include <QDialogButtonBox>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSet>
#include <QStringList>
#include <QVBoxLayout>

V2WindowsForwardTargetDialog::V2WindowsForwardTargetDialog(
        const QVector<V2WindowsConversationDirectoryViewModel::Row> &authorizedRows,
        const QString &sourceConversationId, QWidget *parent,
        bool forwardingEnabled)
    : QDialog(parent), m_status(new QLabel(this)),
      m_targets(new QListWidget(this)), m_forward(nullptr) {
    setWindowTitle(QStringLiteral("选择转发目标"));
    setAccessibleName(QStringLiteral("转发目标选择窗口"));
    setMinimumSize(420, 360);

    auto *explanation = new QLabel(
        QStringLiteral("服务器会校验你对原会话和目标会话的权限。"
                       "目标消息不会显示原会话或原发送者。"),
        this);
    explanation->setWordWrap(true);
    explanation->setAccessibleName(QStringLiteral("转发隐私与权限说明"));
    m_status->setWordWrap(true);
    m_status->setAccessibleName(QStringLiteral("转发目标状态"));
    m_targets->setAccessibleName(QStringLiteral("可转发的会话列表"));
    m_targets->setSelectionMode(QAbstractItemView::SingleSelection);

    auto *buttons = new QDialogButtonBox(QDialogButtonBox::Cancel, this);
    m_forward = buttons->addButton(QStringLiteral("转发"), QDialogButtonBox::AcceptRole);
    m_forward->setAccessibleName(QStringLiteral("确认转发到所选会话"));
    m_forward->setEnabled(false);

    auto *layout = new QVBoxLayout(this);
    layout->addWidget(explanation);
    layout->addWidget(m_status);
    layout->addWidget(m_targets, 1);
    layout->addWidget(buttons);

    if (!forwardingEnabled) {
        m_status->setText(QStringLiteral("转发功能尚未启用"));
        m_targets->setEnabled(false);
    } else if (sourceConversationId.isEmpty()) {
        m_status->setText(QStringLiteral("无法加载转发目标"));
        m_targets->setEnabled(false);
    } else {
        QSet<QString> identities;
        for (const auto &row : authorizedRows) {
            if (row.conversationId.isEmpty()
                    || row.conversationId == sourceConversationId
                    || row.displayName.trimmed().isEmpty()
                    || identities.contains(row.conversationId)) {
                continue;
            }
            identities.insert(row.conversationId);
            QStringList details;
            if (!row.kindLabel.isEmpty()) details.append(row.kindLabel);
            if (!row.roleLabel.isEmpty()) details.append(row.roleLabel);
            const QString suffix = details.isEmpty()
                ? QString() : QStringLiteral("\n%1").arg(details.join(QStringLiteral(" · ")));
            auto *item = new QListWidgetItem(row.displayName + suffix, m_targets);
            item->setData(Qt::UserRole, row.conversationId);
            item->setToolTip(row.displayName);
        }
        m_status->setText(m_targets->count() == 0
            ? QStringLiteral("没有可用的其他会话")
            : QStringLiteral("请选择一个目标会话"));
    }

    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_forward, &QPushButton::clicked,
            this, &V2WindowsForwardTargetDialog::submitSelection);
    connect(m_targets, &QListWidget::currentItemChanged,
            this, [this] { updateSelection(); });
    connect(m_targets, &QListWidget::itemActivated,
            this, [this](QListWidgetItem *) { submitSelection(); });
}

void V2WindowsForwardTargetDialog::updateSelection() {
    const auto *item = m_targets->currentItem();
    m_forward->setEnabled(
        m_targets->isEnabled() && item && !item->data(Qt::UserRole).toString().isEmpty());
}

void V2WindowsForwardTargetDialog::submitSelection() {
    const auto *item = m_targets->currentItem();
    if (!m_forward->isEnabled() || !item) return;
    const QString conversationId = item->data(Qt::UserRole).toString();
    if (conversationId.isEmpty()) return;
    m_selectedConversationId = conversationId;
    accept();
}
