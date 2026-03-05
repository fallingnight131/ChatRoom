#include "UserInfoDialog.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QMenu>
#include <QEvent>
#include <QMouseEvent>
#include <QDialog>
#include <QPainter>
#include <QPainterPath>

static QPixmap roundedPixmap(const QPixmap &src, int radius) {
    QPixmap dst(src.size());
    dst.fill(Qt::transparent);
    QPainter p(&dst);
    p.setRenderHint(QPainter::Antialiasing);
    QPainterPath path;
    path.addRoundedRect(QRectF(dst.rect()), radius, radius);
    p.setClipPath(path);
    p.drawPixmap(0, 0, src);
    return dst;
}

UserInfoDialog::UserInfoDialog(const QString &username, const QString &displayName,
                               const QPixmap &avatar, const QString &role,
                               QWidget *parent)
    : QDialog(parent), m_avatar(avatar)
{
    setWindowTitle(QStringLiteral("用户信息"));
    setMinimumWidth(280);
    auto *layout = new QVBoxLayout(this);

    // --- 头像 ---
    auto *avatarCenter = new QHBoxLayout;
    avatarCenter->addStretch();

    m_avatarLabel = new QLabel;
    m_avatarLabel->setFixedSize(80, 80);
    m_avatarLabel->setStyleSheet("border: 2px solid #ccc; border-radius: 12px; background: #ddd;");
    m_avatarLabel->setScaledContents(true);
    m_avatarLabel->setAlignment(Qt::AlignCenter);
    m_avatarLabel->setContextMenuPolicy(Qt::CustomContextMenu);

    if (!avatar.isNull()) {
        m_avatarLabel->setPixmap(roundedPixmap(avatar.scaled(80, 80, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation), 10));
    } else {
        m_avatarLabel->setText(displayName.isEmpty() ? "?" : displayName.left(1).toUpper());
    }

    // 右键头像 → 查看大图
    connect(m_avatarLabel, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
        QMenu menu(this);
        menu.addAction(QStringLiteral("查看大图"), this, &UserInfoDialog::viewLargeAvatar);
        menu.exec(m_avatarLabel->mapToGlobal(pos));
    });

    // 双击头像 → 直接查看大图（通过事件过滤器）
    m_avatarLabel->installEventFilter(this);

    avatarCenter->addWidget(m_avatarLabel);
    avatarCenter->addStretch();
    layout->addLayout(avatarCenter);
    layout->addSpacing(8);

    // --- 昵称 ---
    auto *nickLabel = new QLabel(QStringLiteral("昵称：%1").arg(displayName));
    nickLabel->setStyleSheet("font-size: 14px; padding: 4px;");
    nickLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(nickLabel);

    // --- 用户ID ---
    auto *idLabel = new QLabel(QStringLiteral("ID：%1").arg(username));
    idLabel->setStyleSheet("font-size: 14px; padding: 4px;");
    idLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(idLabel);

    // --- 权限 ---
    if (!role.isEmpty()) {
        auto *roleLabel = new QLabel(QStringLiteral("权限：%1").arg(role));
        roleLabel->setStyleSheet("font-size: 14px; padding: 4px;");
        layout->addWidget(roleLabel);
    }

    layout->addSpacing(8);

    // --- 底部关闭 ---
    auto *bottomLayout = new QHBoxLayout;
    bottomLayout->addStretch();
    auto *closeBtn = new QPushButton(QStringLiteral("关闭"));
    connect(closeBtn, &QPushButton::clicked, this, &QDialog::close);
    bottomLayout->addWidget(closeBtn);
    layout->addLayout(bottomLayout);
}

bool UserInfoDialog::eventFilter(QObject *watched, QEvent *event) {
    if (watched == m_avatarLabel && event->type() == QEvent::MouseButtonDblClick) {
        viewLargeAvatar();
        return true;
    }
    return QDialog::eventFilter(watched, event);
}

void UserInfoDialog::viewLargeAvatar() {
    if (m_avatar.isNull()) return;

    auto *dlg = new QDialog(this);
    dlg->setWindowTitle(QStringLiteral("头像大图"));
    dlg->setAttribute(Qt::WA_DeleteOnClose);
    auto *layout = new QVBoxLayout(dlg);
    layout->setContentsMargins(4, 4, 4, 4);

    auto *label = new QLabel;
    QPixmap scaled = m_avatar.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    label->setPixmap(scaled);
    label->setAlignment(Qt::AlignCenter);
    layout->addWidget(label);

    dlg->resize(scaled.size() + QSize(8, 8));
    dlg->show();
}
