#include "ProfileDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"
#include "WindowsBandwidthViewModel.h"
#include "WindowsLocaleViewModel.h"

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
#include <QCheckBox>
#include <QSignalBlocker>
#include <QAccessible>
#include <QComboBox>

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
                             QWidget *parent,
                             WindowsBandwidthViewModel *bandwidthViewModel,
                             WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_userId(userId), m_username(username), m_displayName(displayName),
      m_bandwidthViewModel(bandwidthViewModel), m_localeViewModel(localeViewModel),
      m_locale(localeViewModel ? localeViewModel->locale() : WindowsLocale::ZhCn)
{
    setMinimumWidth(400);
    auto *mainLayout = new QVBoxLayout(this);

    if (m_localeViewModel) {
        auto *localeRow = new QHBoxLayout;
        m_localeLabel = new QLabel;
        m_localeSelector = new QComboBox;
        m_localeStatus = new QLabel;
        m_localeSelector->addItem({}, static_cast<int>(WindowsLocale::ZhCn));
        m_localeSelector->addItem({}, static_cast<int>(WindowsLocale::EnUs));
        m_localeLabel->setBuddy(m_localeSelector);
        m_localeStatus->setWordWrap(true);
        localeRow->addWidget(m_localeLabel);
        localeRow->addWidget(m_localeSelector);
        localeRow->addWidget(m_localeStatus, 1);
        mainLayout->addLayout(localeRow);
        connect(m_localeSelector, qOverload<int>(&QComboBox::currentIndexChanged),
                this, [this](int index) {
                    m_localeViewModel->select(static_cast<WindowsLocale>(
                        m_localeSelector->itemData(index).toInt()));
                });
    }

    // --- 头像区域 ---
    m_avatarGroup = new QGroupBox;
    auto *avatarLayout = new QVBoxLayout(m_avatarGroup);
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
        m_avatarLabel->setText(
            WindowsLocaleCatalog::messages(m_locale).profileAvatarFallback);
    }
    avatarCenter->addWidget(m_avatarLabel);
    avatarCenter->addStretch();
    avatarLayout->addLayout(avatarCenter);

    auto *avatarBtnLayout = new QHBoxLayout;
    avatarBtnLayout->addStretch();
    m_changeAvatar = new QPushButton;
    connect(m_changeAvatar, &QPushButton::clicked, this, [this] {
        emit changeAvatarRequested();
    });
    avatarBtnLayout->addWidget(m_changeAvatar);
    avatarBtnLayout->addStretch();
    avatarLayout->addLayout(avatarBtnLayout);

    mainLayout->addWidget(m_avatarGroup);

    // --- 基本信息 ---
    m_infoGroup = new QGroupBox;
    auto *infoLayout = new QVBoxLayout(m_infoGroup);

    // 昵称
    auto *nickLayout = new QHBoxLayout;
    m_nicknameLabel = new QLabel;
    nickLayout->addWidget(m_nicknameLabel);
    m_nicknameEdit = new QLineEdit(displayName);
    m_nicknameEdit->setMaxLength(20);
    nickLayout->addWidget(m_nicknameEdit, 1);
    m_saveNickname = new QPushButton;
    connect(m_saveNickname, &QPushButton::clicked, this, &ProfileDialog::onSaveNickname);
    nickLayout->addWidget(m_saveNickname);
    infoLayout->addLayout(nickLayout);

    // 用户ID
    auto *uidLayout = new QHBoxLayout;
    m_uidLabel = new QLabel;
    uidLayout->addWidget(m_uidLabel);
    m_uidEdit = new QLineEdit(username);
    m_uidEdit->setMaxLength(20);
    uidLayout->addWidget(m_uidEdit, 1);
    m_saveUid = new QPushButton;
    connect(m_saveUid, &QPushButton::clicked, this, &ProfileDialog::onSaveUid);
    uidLayout->addWidget(m_saveUid);
    infoLayout->addLayout(uidLayout);

    m_uidHint = new QLabel;
    m_uidHint->setStyleSheet("color: gray; font-size: 11px;");
    infoLayout->addWidget(m_uidHint);

    mainLayout->addWidget(m_infoGroup);

    if (m_bandwidthViewModel) {
        m_bandwidthGroup = new QGroupBox;
        auto *bandwidthLayout = new QVBoxLayout(m_bandwidthGroup);
        m_lowBandwidth = new QCheckBox;
        m_lowBandwidth->setChecked(m_bandwidthViewModel->enabled());
        m_bandwidthDescription = new QLabel;
        m_bandwidthDescription->setWordWrap(true);
        m_bandwidthStatus = new QLabel;
        m_bandwidthStatus->setWordWrap(true);
        bandwidthLayout->addWidget(m_lowBandwidth);
        bandwidthLayout->addWidget(m_bandwidthDescription);
        bandwidthLayout->addWidget(m_bandwidthStatus);
        mainLayout->addWidget(m_bandwidthGroup);
        connect(m_lowBandwidth, &QCheckBox::toggled,
                m_bandwidthViewModel, &WindowsBandwidthViewModel::select);
        connect(m_bandwidthViewModel, &WindowsBandwidthViewModel::changed,
                this, [this] {
                    const QSignalBlocker blocker(m_lowBandwidth);
                    m_lowBandwidth->setChecked(m_bandwidthViewModel->enabled());
                    m_bandwidthStatus->setText(m_bandwidthViewModel->saveFailed()
                        ? WindowsLocaleCatalog::messages(m_locale)
                              .profileLowBandwidthSaveFailed
                        : QString());
                    if (m_bandwidthViewModel->saveFailed() && isVisible()) {
                        QAccessibleEvent announcement(
                            m_bandwidthStatus, QAccessible::Alert);
                        QAccessible::updateAccessibility(&announcement);
                    }
                });
    }

    // --- 修改密码 ---
    m_passwordGroup = new QGroupBox;
    auto *pwdLayout = new QFormLayout(m_passwordGroup);

    m_oldPwdEdit = new QLineEdit;
    m_oldPwdEdit->setEchoMode(QLineEdit::Password);
    m_oldPasswordLabel = new QLabel;
    pwdLayout->addRow(m_oldPasswordLabel, m_oldPwdEdit);

    m_newPwdEdit = new QLineEdit;
    m_newPwdEdit->setEchoMode(QLineEdit::Password);
    m_newPasswordLabel = new QLabel;
    pwdLayout->addRow(m_newPasswordLabel, m_newPwdEdit);

    m_confirmPwdEdit = new QLineEdit;
    m_confirmPwdEdit->setEchoMode(QLineEdit::Password);
    m_confirmPasswordLabel = new QLabel;
    pwdLayout->addRow(m_confirmPasswordLabel, m_confirmPwdEdit);

    auto *pwdBtnLayout = new QHBoxLayout;
    pwdBtnLayout->addStretch();
    m_changePassword = new QPushButton;
    connect(m_changePassword, &QPushButton::clicked, this, &ProfileDialog::onChangePassword);
    pwdBtnLayout->addWidget(m_changePassword);
    pwdLayout->addRow(pwdBtnLayout);

    mainLayout->addWidget(m_passwordGroup);

    // --- 底部关闭 ---
    auto *bottomLayout = new QHBoxLayout;
    bottomLayout->addStretch();
    m_close = new QPushButton;
    connect(m_close, &QPushButton::clicked, this, &QDialog::close);
    bottomLayout->addWidget(m_close);
    mainLayout->addLayout(bottomLayout);

    // 连接密码修改响应
    connect(NetworkManager::instance(), &NetworkManager::changePasswordResponse,
            this, [this](bool success, const QString &error) {
        if (success) {
            const auto &copy = WindowsLocaleCatalog::messages(m_locale);
            QMessageBox::information(this, copy.profileSuccessTitle,
                                     copy.profilePasswordChanged);
            m_oldPwdEdit->clear();
            m_newPwdEdit->clear();
            m_confirmPwdEdit->clear();
        } else {
            QMessageBox::warning(this,
                WindowsLocaleCatalog::messages(m_locale)
                    .profilePasswordChangeFailedTitle,
                error);
        }
    });
    if (m_localeViewModel)
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &ProfileDialog::applyLocale);
    applyLocale();
}

void ProfileDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.profileTitle);
    if (m_localeSelector) {
        m_localeLabel->setText(copy.language);
        m_localeSelector->setAccessibleName(copy.languageSelectorAccessible);
        m_localeSelector->setAccessibleDescription(
            copy.languageSelectorDescription);
        m_localeStatus->setAccessibleName(copy.localePreferenceStatusAccessible);
        m_localeStatus->setText(m_localeViewModel->failure());
        if (!m_localeViewModel->failure().isEmpty() && isVisible()) {
            QAccessibleEvent announcement(m_localeStatus, QAccessible::Alert);
            QAccessible::updateAccessibility(&announcement);
        }
        const QSignalBlocker blocker(m_localeSelector);
        m_localeSelector->setItemText(0, copy.chinese);
        m_localeSelector->setItemText(1, copy.english);
        m_localeSelector->setCurrentIndex(
            m_locale == WindowsLocale::EnUs ? 1 : 0);
    }
    m_avatarGroup->setTitle(copy.profileAvatar);
    if (m_avatarLabel->pixmap().isNull())
        m_avatarLabel->setText(copy.profileAvatarFallback);
    m_changeAvatar->setText(copy.profileChangeAvatar);
    m_infoGroup->setTitle(copy.profileBasicInformation);
    m_nicknameLabel->setText(copy.profileNickname);
    m_nicknameLabel->setBuddy(m_nicknameEdit);
    m_saveNickname->setText(copy.profileSave);
    m_uidLabel->setText(copy.profileUserId);
    m_uidLabel->setBuddy(m_uidEdit);
    m_saveUid->setText(copy.profileSave);
    m_uidHint->setText(copy.profileUserIdHint);
    if (m_bandwidthGroup) {
        m_bandwidthGroup->setTitle(copy.profileNetworkAndData);
        m_lowBandwidth->setText(copy.profileLowBandwidth);
        m_lowBandwidth->setAccessibleDescription(copy.profileLowBandwidthDescription);
        m_bandwidthDescription->setText(copy.profileLowBandwidthDescription);
        m_bandwidthStatus->setAccessibleName(
            copy.profileLowBandwidthStatusAccessible);
        if (m_bandwidthViewModel->saveFailed())
            m_bandwidthStatus->setText(copy.profileLowBandwidthSaveFailed);
    }
    m_passwordGroup->setTitle(copy.profileChangePassword);
    m_oldPasswordLabel->setText(copy.profileOldPassword);
    m_oldPasswordLabel->setBuddy(m_oldPwdEdit);
    m_newPasswordLabel->setText(copy.profileNewPassword);
    m_newPasswordLabel->setBuddy(m_newPwdEdit);
    m_confirmPasswordLabel->setText(copy.profileConfirmPassword);
    m_confirmPasswordLabel->setBuddy(m_confirmPwdEdit);
    m_changePassword->setText(copy.profileChangePassword);
    m_close->setText(copy.close);
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
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    if (newName.isEmpty()) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profileNicknameRequired);
        return;
    }
    if (newName.length() > 20) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profileNicknameTooLong);
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
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    if (newUid.isEmpty()) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profileUserIdRequired);
        return;
    }
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(newUid).hasMatch()) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profileUserIdInvalid);
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
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);

    if (oldPwd.isEmpty() || newPwd.isEmpty()) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profilePasswordFieldsRequired);
        return;
    }
    if (newPwd != confirmPwd) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profilePasswordsMismatch);
        return;
    }
    if (newPwd.length() < 4) {
        QMessageBox::warning(this, copy.profileValidationErrorTitle,
                             copy.profilePasswordTooShort);
        return;
    }

    NetworkManager::instance()->changePassword(oldPwd, newPwd);
}
