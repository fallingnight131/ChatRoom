#include "ProfileDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QGroupBox>
#include <QMessageBox>
#include <QRegularExpression>
#include <QPixmap>
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

ProfileDialog::ProfileDialog(int userId, const QString &username,
                             const QString &displayName, const QPixmap &avatar,
                             QWidget *parent)
    : QDialog(parent), m_userId(userId), m_username(username), m_displayName(displayName)
{
    setWindowTitle(QStringLiteral("修改个人信息"));
    setMinimumWidth(400);
    auto *mainLayout = new QVBoxLayout(this);

    // --- 头像区域 ---
    auto *avatarGroup = new QGroupBox(QStringLiteral("头像"));
    auto *avatarLayout = new QVBoxLayout(avatarGroup);
    auto *avatarCenter = new QHBoxLayout;
    avatarCenter->addStretch();

    m_avatarLabel = new QLabel;
    m_avatarLabel->setFixedSize(80, 80);
    m_avatarLabel->setStyleSheet("border: 2px solid #ccc; border-radius: 12px; background: #ddd;");
    m_avatarLabel->setScaledContents(true);
    m_avatarLabel->setAlignment(Qt::AlignCenter);
    if (!avatar.isNull()) {
        m_avatarLabel->setPixmap(roundedPixmap(avatar.scaled(80, 80, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation), 10));
    } else {
        m_avatarLabel->setText(QStringLiteral("头像"));
    }
    avatarCenter->addWidget(m_avatarLabel);
    avatarCenter->addStretch();
    avatarLayout->addLayout(avatarCenter);

    auto *avatarBtnLayout = new QHBoxLayout;
    avatarBtnLayout->addStretch();
    auto *changeAvatarBtn = new QPushButton(QStringLiteral("更换头像"));
    connect(changeAvatarBtn, &QPushButton::clicked, this, [this] {
        emit changeAvatarRequested();
    });
    avatarBtnLayout->addWidget(changeAvatarBtn);
    avatarBtnLayout->addStretch();
    avatarLayout->addLayout(avatarBtnLayout);

    mainLayout->addWidget(avatarGroup);

    // --- 基本信息 ---
    auto *infoGroup = new QGroupBox(QStringLiteral("基本信息"));
    auto *infoLayout = new QVBoxLayout(infoGroup);

    // 昵称
    auto *nickLayout = new QHBoxLayout;
    nickLayout->addWidget(new QLabel(QStringLiteral("昵称：")));
    m_nicknameEdit = new QLineEdit(displayName);
    m_nicknameEdit->setMaxLength(20);
    nickLayout->addWidget(m_nicknameEdit, 1);
    auto *saveNickBtn = new QPushButton(QStringLiteral("保存"));
    connect(saveNickBtn, &QPushButton::clicked, this, &ProfileDialog::onSaveNickname);
    nickLayout->addWidget(saveNickBtn);
    infoLayout->addLayout(nickLayout);

    // 用户ID
    auto *uidLayout = new QHBoxLayout;
    uidLayout->addWidget(new QLabel(QStringLiteral("用户ID：")));
    m_uidEdit = new QLineEdit(username);
    m_uidEdit->setMaxLength(20);
    uidLayout->addWidget(m_uidEdit, 1);
    auto *saveUidBtn = new QPushButton(QStringLiteral("保存"));
    connect(saveUidBtn, &QPushButton::clicked, this, &ProfileDialog::onSaveUid);
    uidLayout->addWidget(saveUidBtn);
    infoLayout->addLayout(uidLayout);

    auto *uidHint = new QLabel(QStringLiteral("6-20位字母/数字/下划线，每月仅可修改一次"));
    uidHint->setStyleSheet("color: gray; font-size: 11px;");
    infoLayout->addWidget(uidHint);

    mainLayout->addWidget(infoGroup);

    // --- 修改密码 ---
    auto *pwdGroup = new QGroupBox(QStringLiteral("修改密码"));
    auto *pwdLayout = new QFormLayout(pwdGroup);

    m_oldPwdEdit = new QLineEdit;
    m_oldPwdEdit->setEchoMode(QLineEdit::Password);
    pwdLayout->addRow(QStringLiteral("旧密码："), m_oldPwdEdit);

    m_newPwdEdit = new QLineEdit;
    m_newPwdEdit->setEchoMode(QLineEdit::Password);
    pwdLayout->addRow(QStringLiteral("新密码："), m_newPwdEdit);

    m_confirmPwdEdit = new QLineEdit;
    m_confirmPwdEdit->setEchoMode(QLineEdit::Password);
    pwdLayout->addRow(QStringLiteral("确认新密码："), m_confirmPwdEdit);

    auto *pwdBtnLayout = new QHBoxLayout;
    pwdBtnLayout->addStretch();
    auto *changePwdBtn = new QPushButton(QStringLiteral("修改密码"));
    connect(changePwdBtn, &QPushButton::clicked, this, &ProfileDialog::onChangePassword);
    pwdBtnLayout->addWidget(changePwdBtn);
    pwdLayout->addRow(pwdBtnLayout);

    mainLayout->addWidget(pwdGroup);

    // --- 底部关闭 ---
    auto *bottomLayout = new QHBoxLayout;
    bottomLayout->addStretch();
    auto *closeBtn = new QPushButton(QStringLiteral("关闭"));
    connect(closeBtn, &QPushButton::clicked, this, &QDialog::close);
    bottomLayout->addWidget(closeBtn);
    mainLayout->addLayout(bottomLayout);

    // 连接密码修改响应
    connect(NetworkManager::instance(), &NetworkManager::changePasswordResponse,
            this, [this](bool success, const QString &error) {
        if (success) {
            QMessageBox::information(this, QStringLiteral("成功"), QStringLiteral("密码修改成功"));
            m_oldPwdEdit->clear();
            m_newPwdEdit->clear();
            m_confirmPwdEdit->clear();
        } else {
            QMessageBox::warning(this, QStringLiteral("修改密码失败"), error);
        }
    });
}

void ProfileDialog::updateAvatar(const QPixmap &avatar) {
    if (!avatar.isNull()) {
        m_avatarLabel->setPixmap(roundedPixmap(avatar.scaled(80, 80, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation), 10));
    }
}

void ProfileDialog::updateDisplayName(const QString &name) {
    m_displayName = name;
    m_nicknameEdit->setText(name);
}

void ProfileDialog::updateUid(const QString &uid) {
    m_username = uid;
    m_uidEdit->setText(uid);
}

void ProfileDialog::onSaveNickname() {
    QString newName = m_nicknameEdit->text().trimmed();
    if (newName.isEmpty()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("昵称不能为空"));
        return;
    }
    if (newName.length() > 20) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("昵称不能超过20个字符"));
        return;
    }
    if (newName == m_displayName) return;

    QJsonObject data;
    data["displayName"] = newName;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::CHANGE_NICKNAME_REQ, data));
}

void ProfileDialog::onSaveUid() {
    QString newUid = m_uidEdit->text().trimmed();
    if (newUid.isEmpty()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("用户ID不能为空"));
        return;
    }
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(newUid).hasMatch()) {
        QMessageBox::warning(this, QStringLiteral("错误"),
                             QStringLiteral("用户ID必须为6-20位字母/数字/下划线"));
        return;
    }
    if (newUid == m_username) return;

    QJsonObject data;
    data["newUid"] = newUid;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::CHANGE_UID_REQ, data));
}

void ProfileDialog::onChangePassword() {
    QString oldPwd     = m_oldPwdEdit->text();
    QString newPwd     = m_newPwdEdit->text();
    QString confirmPwd = m_confirmPwdEdit->text();

    if (oldPwd.isEmpty() || newPwd.isEmpty()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("请填写所有密码字段"));
        return;
    }
    if (newPwd != confirmPwd) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("两次输入的新密码不一致"));
        return;
    }
    if (newPwd.length() < 4) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("新密码至少4个字符"));
        return;
    }

    QJsonObject data;
    data["oldPassword"] = oldPwd;
    data["newPassword"] = newPwd;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::CHANGE_PASSWORD_REQ, data));
}
