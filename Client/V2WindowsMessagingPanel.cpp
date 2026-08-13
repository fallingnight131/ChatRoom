#include "V2WindowsMessagingPanel.h"
#include "V2WindowsMessagingViewModel.h"

#include <QHBoxLayout>
#include <QInputDialog>
#include <QLabel>
#include <QListWidget>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QVBoxLayout>

V2WindowsMessagingPanel::V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel, QWidget *parent)
    : QWidget(parent), m_viewModel(viewModel), m_status(new QLabel(this)),
      m_replyBanner(new QLabel(this)), m_messages(new QListWidget(this)),
      m_composer(new QPlainTextEdit(this)),
      m_cancelReply(new QPushButton(QStringLiteral("取消回复"), this)),
      m_send(new QPushButton(QStringLiteral("发送回复"), this)) {
    Q_ASSERT(m_viewModel);
    setAccessibleName(QStringLiteral("消息和回复"));
    m_status->setAccessibleName(QStringLiteral("消息状态"));
    m_status->setWordWrap(true);
    m_replyBanner->setAccessibleName(QStringLiteral("当前回复目标"));
    m_replyBanner->setWordWrap(true);
    m_messages->setAccessibleName(QStringLiteral("消息列表"));
    m_messages->setSelectionMode(QAbstractItemView::NoSelection);
    m_composer->setAccessibleName(QStringLiteral("回复内容"));
    m_composer->setPlaceholderText(QStringLiteral("输入回复内容"));
    m_composer->setMaximumBlockCount(1000);
    m_cancelReply->setAccessibleName(QStringLiteral("取消当前回复"));
    m_send->setAccessibleName(QStringLiteral("发送当前回复"));

    auto *replyHeader = new QHBoxLayout;
    replyHeader->addWidget(m_replyBanner, 1);
    replyHeader->addWidget(m_cancelReply);
    auto *composerRow = new QHBoxLayout;
    composerRow->addWidget(m_composer, 1);
    composerRow->addWidget(m_send);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(m_status);
    layout->addWidget(m_messages, 1);
    layout->addLayout(replyHeader);
    layout->addLayout(composerRow);

    connect(m_viewModel, &V2WindowsMessagingViewModel::changed,
            this, &V2WindowsMessagingPanel::render);
    connect(m_viewModel, &V2WindowsMessagingViewModel::focusComposerRequested,
            m_composer, qOverload<>(&QWidget::setFocus));
    connect(m_cancelReply, &QPushButton::clicked,
            m_viewModel, &V2WindowsMessagingViewModel::cancelReply);
    connect(m_send, &QPushButton::clicked, this, &V2WindowsMessagingPanel::sendReply);
    connect(m_composer, &QPlainTextEdit::textChanged, this, [this] {
        m_send->setEnabled(!m_viewModel->replyTargetMessageId().isEmpty()
            && !m_composer->toPlainText().trimmed().isEmpty());
    });
    render();
}

void V2WindowsMessagingPanel::render() {
    m_status->setText(m_viewModel->failure());
    m_messages->clear();
    for (const auto &message : m_viewModel->rows()) {
        auto *item = new QListWidgetItem(m_messages);
        auto *row = new QWidget(m_messages);
        auto *layout = new QVBoxLayout(row);
        auto *body = new QLabel(message.text, row);
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
            auto *reply = new QPushButton(QStringLiteral("回复"), row);
            reply->setAccessibleName(QStringLiteral("回复此消息"));
            connect(reply, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.messageId] { model->chooseReply(id); });
            actions->addWidget(reply);
            if (message.canEdit) {
                auto *edit = new QPushButton(QStringLiteral("编辑"), row);
                edit->setAccessibleName(QStringLiteral("编辑此消息"));
                connect(edit, &QPushButton::clicked, m_viewModel,
                    [model = m_viewModel, id = message.messageId,
                     current = message.text, row] {
                        bool accepted = false;
                        const QString text = QInputDialog::getMultiLineText(
                            row, QStringLiteral("编辑消息"), QStringLiteral("消息内容"),
                            current, &accepted);
                        if (accepted && !text.trimmed().isEmpty())
                            model->editMessage(id, text);
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
    const bool replying = !m_viewModel->replyTargetMessageId().isEmpty();
    m_replyBanner->setText(m_viewModel->replyBanner());
    m_replyBanner->setVisible(replying);
    m_cancelReply->setVisible(replying);
    m_send->setEnabled(replying && !m_composer->toPlainText().trimmed().isEmpty());
}

void V2WindowsMessagingPanel::sendReply() {
    if (m_viewModel->sendReply(m_composer->toPlainText())) {
        m_composer->clear();
        m_composer->setFocus();
    }
}
