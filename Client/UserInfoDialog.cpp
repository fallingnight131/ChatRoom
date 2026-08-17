#include "UserInfoDialog.h"
#include "WindowsLocaleViewModel.h"

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
                               const QPixmap &avatar, Role role, QWidget *parent,
                               WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_avatar(avatar), m_username(username),
      m_displayName(displayName), m_role(role),
      m_localeViewModel(localeViewModel)
{
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
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
    m_avatarLabel->setFocusPolicy(Qt::StrongFocus);

    if (!avatar.isNull()) {
        m_avatarLabel->setPixmap(roundedPixmap(avatar.scaled(80, 80, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation), 10));
    } else {
        m_avatarLabel->setText(displayName.isEmpty() ? "?" : displayName.left(1).toUpper());
    }

    // 右键头像 → 查看大图
    connect(m_avatarLabel, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
        QMenu menu(this);
        menu.addAction(
            WindowsLocaleCatalog::messages(m_locale).userInfoViewLargeAvatar,
            this, &UserInfoDialog::viewLargeAvatar);
        menu.exec(m_avatarLabel->mapToGlobal(pos));
    });

    // 双击头像 → 直接查看大图（通过事件过滤器）
    m_avatarLabel->installEventFilter(this);

    avatarCenter->addWidget(m_avatarLabel);
    avatarCenter->addStretch();
    layout->addLayout(avatarCenter);
    layout->addSpacing(8);

    // --- 昵称 ---
    m_nicknameLabel = new QLabel;
    m_nicknameLabel->setStyleSheet("font-size: 14px; padding: 4px;");
    m_nicknameLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(m_nicknameLabel);

    // --- 用户ID ---
    m_idLabel = new QLabel;
    m_idLabel->setStyleSheet("font-size: 14px; padding: 4px;");
    m_idLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(m_idLabel);

    // --- 权限 ---
    if (m_role != Role::None) {
        m_roleLabel = new QLabel;
        m_roleLabel->setStyleSheet("font-size: 14px; padding: 4px;");
        layout->addWidget(m_roleLabel);
    }

    layout->addSpacing(8);

    // --- 底部关闭 ---
    auto *bottomLayout = new QHBoxLayout;
    bottomLayout->addStretch();
    m_closeButton = new QPushButton;
    connect(m_closeButton, &QPushButton::clicked, this, &QDialog::close);
    bottomLayout->addWidget(m_closeButton);
    layout->addLayout(bottomLayout);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &UserInfoDialog::applyLocale);
    }
    applyLocale();
}

QString UserInfoDialog::roleText() const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    return m_role == Role::Administrator
        ? copy.userInfoAdministrator : copy.userInfoMember;
}

void UserInfoDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.userInfoTitle);
    const QString avatarName = m_displayName.trimmed().isEmpty()
        ? m_username : m_displayName;
    m_avatarLabel->setAccessibleName(copy.userInfoAvatarAccessible.arg(avatarName));
    m_avatarLabel->setToolTip(copy.userInfoViewLargeAvatar);
    m_nicknameLabel->setText(copy.userInfoNickname.arg(m_displayName));
    m_idLabel->setText(copy.userInfoId.arg(m_username));
    if (m_roleLabel) m_roleLabel->setText(copy.userInfoRole.arg(roleText()));
    m_closeButton->setText(copy.userInfoClose);
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
    dlg->setWindowTitle(
        WindowsLocaleCatalog::messages(m_locale).userInfoLargeAvatarTitle);
    dlg->setAttribute(Qt::WA_DeleteOnClose);
    auto *layout = new QVBoxLayout(dlg);
    layout->setContentsMargins(4, 4, 4, 4);

    auto *label = new QLabel;
    QPixmap scaled = m_avatar.scaled(400, 400, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    label->setPixmap(scaled);
    label->setAlignment(Qt::AlignCenter);
    layout->addWidget(label);

    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed, dlg,
                [this, dlg, label] {
                    const auto &copy = WindowsLocaleCatalog::messages(
                        m_localeViewModel->locale());
                    dlg->setWindowTitle(copy.userInfoLargeAvatarTitle);
                    const QString avatarName = m_displayName.trimmed().isEmpty()
                        ? m_username : m_displayName;
                    label->setAccessibleName(
                        copy.userInfoAvatarAccessible.arg(avatarName));
                });
    }
    label->setAccessibleName(
        WindowsLocaleCatalog::messages(m_locale)
            .userInfoAvatarAccessible.arg(
                m_displayName.trimmed().isEmpty() ? m_username : m_displayName));

    dlg->resize(scaled.size() + QSize(8, 8));
    dlg->show();
}
