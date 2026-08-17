#include "RoomSettingsDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"
#include "AvatarCropDialog.h"
#include "WindowsLocaleViewModel.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QLabel>
#include <QLineEdit>
#include <QDoubleSpinBox>
#include <QSpinBox>
#include <QPushButton>
#include <QGroupBox>
#include <QMessageBox>
#include <QInputDialog>
#include <QFileDialog>
#include <QBuffer>
#include <QPixmap>
#include <QImage>

RoomSettingsDialog::RoomSettingsDialog(int roomId, const QString &roomName,
                                       bool isAdmin,
                                       qint64 maxFileSize,
                                       qint64 totalFileSpace,
                                       int maxFileCount,
                                       int maxMembers,
                                       QWidget *parent,
                                       WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_roomId(roomId), m_roomName(roomName),
      m_isAdmin(isAdmin), m_localeViewModel(localeViewModel)
{
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    setMinimumWidth(380);
    auto *mainLayout = new QVBoxLayout(this);

    // --- 聊天室头像 + ID 信息 ---
    auto *headerLayout = new QHBoxLayout;
    m_avatarPreview = new QLabel;
    m_avatarPreview->setFixedSize(48, 48);
    m_avatarPreview->setAlignment(Qt::AlignCenter);
    m_avatarPreview->setStyleSheet("border: 1px solid #ccc; border-radius: 6px; font-size: 22px;");
    m_avatarPreview->setText("🏠");
    headerLayout->addWidget(m_avatarPreview);

    auto *headerInfoLayout = new QVBoxLayout;
    headerInfoLayout->setSpacing(2);
    m_roomNameLabel = new QLabel(roomName);
    m_roomNameLabel->setStyleSheet("font-weight: bold; font-size: 14px;");
    m_roomIdLabel = new QLabel(QStringLiteral("ID: ") + QString::number(roomId));
    m_roomIdLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_roomIdLabel->setStyleSheet("color: gray; font-size: 12px;");
    headerInfoLayout->addWidget(m_roomNameLabel);
    headerInfoLayout->addWidget(m_roomIdLabel);
    headerLayout->addLayout(headerInfoLayout, 1);
    mainLayout->addLayout(headerLayout);

    // 请求聊天室头像
    {
        QJsonObject reqData;
        reqData["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_GET_REQ, reqData));
        // 监听头像响应
        connect(NetworkManager::instance(), &NetworkManager::roomAvatarGetResponse,
            this, [this](int rId, bool success, const QByteArray &avatarData) {
            if (rId == m_roomId && success && !avatarData.isEmpty()) {
                QPixmap pix;
                pix.loadFromData(avatarData);
                if (!pix.isNull()) {
                    m_avatarPreview->setPixmap(pix.scaled(48, 48, Qt::KeepAspectRatio, Qt::SmoothTransformation));
                }
            }
        });
    }

    mainLayout->addSpacing(8);

    // --- 所有人可见：当前限制 ---
    m_limitsGroup = new QGroupBox;
    auto *limitsForm = new QFormLayout(m_limitsGroup);
    m_maxFileSizeLabel = new QLabel;
    m_totalFileSpaceLabel = new QLabel;
    m_maxFileCountLabel = new QLabel;
    m_maxMembersLabel = new QLabel;
    limitsForm->addRow(m_maxFileSizeLabel,
                       new QLabel(QString("%1 GB").arg(maxFileSize / (1024.0 * 1024.0 * 1024.0), 0, 'f', 1)));
    limitsForm->addRow(m_totalFileSpaceLabel,
                       new QLabel(QString("%1 GB").arg(totalFileSpace / 1024 / 1024 / 1024)));
    limitsForm->addRow(m_maxFileCountLabel,
                       new QLabel(QString::number(maxFileCount)));
    limitsForm->addRow(m_maxMembersLabel,
                       new QLabel(QString::number(maxMembers)));
    mainLayout->addWidget(m_limitsGroup);
    mainLayout->addSpacing(8);

    m_limitsEditGroup = new QGroupBox;
    auto *limitsEditLayout = new QVBoxLayout(m_limitsEditGroup);

    auto *fileLayout = new QHBoxLayout;
    m_editMaxFileSizeLabel = new QLabel;
    fileLayout->addWidget(m_editMaxFileSizeLabel);
    m_fileSizeSpin = new QDoubleSpinBox;
    m_fileSizeSpin->setRange(0.1, 10240.0);
    m_fileSizeSpin->setDecimals(1);
    m_fileSizeSpin->setSingleStep(0.1);
    m_fileSizeSpin->setSuffix(" GB");
    double currentGB = maxFileSize / (1024.0 * 1024.0 * 1024.0);
    m_fileSizeSpin->setValue(currentGB);
    fileLayout->addWidget(m_fileSizeSpin, 1);
    limitsEditLayout->addLayout(fileLayout);

    auto *totalLayout = new QHBoxLayout;
    m_editTotalFileSpaceLabel = new QLabel;
    totalLayout->addWidget(m_editTotalFileSpaceLabel);
    m_totalSpaceSpin = new QDoubleSpinBox;
    m_totalSpaceSpin->setRange(1.0, 10240.0);
    m_totalSpaceSpin->setDecimals(0);
    m_totalSpaceSpin->setSuffix(" GB");
    m_totalSpaceSpin->setValue(totalFileSpace / (1024.0 * 1024.0 * 1024.0));
    totalLayout->addWidget(m_totalSpaceSpin, 1);
    limitsEditLayout->addLayout(totalLayout);

    auto *countLayout = new QHBoxLayout;
    m_editFileCountLabel = new QLabel;
    countLayout->addWidget(m_editFileCountLabel);
    m_fileCountSpin = new QSpinBox;
    m_fileCountSpin->setRange(1, 1000000);
    m_fileCountSpin->setValue(maxFileCount);
    countLayout->addWidget(m_fileCountSpin, 1);
    limitsEditLayout->addLayout(countLayout);

    auto *memberLayout = new QHBoxLayout;
    m_editMemberLimitLabel = new QLabel;
    memberLayout->addWidget(m_editMemberLimitLabel);
    m_memberLimitSpin = new QSpinBox;
    m_memberLimitSpin->setRange(2, 1000000);
    m_memberLimitSpin->setValue(maxMembers);
    memberLayout->addWidget(m_memberLimitSpin, 1);
    limitsEditLayout->addLayout(memberLayout);

    auto *keyLayout = new QHBoxLayout;
    m_developerKeyLabel = new QLabel;
    keyLayout->addWidget(m_developerKeyLabel);
    m_developerKeyEdit = new QLineEdit;
    m_developerKeyEdit->setEchoMode(QLineEdit::Password);
    keyLayout->addWidget(m_developerKeyEdit, 1);
    m_saveLimitsButton = new QPushButton;
    connect(m_saveLimitsButton, &QPushButton::clicked,
            this, &RoomSettingsDialog::onSaveLimits);
    keyLayout->addWidget(m_saveLimitsButton);
    limitsEditLayout->addLayout(keyLayout);

    mainLayout->addWidget(m_limitsEditGroup);
    mainLayout->addSpacing(8);

    if (isAdmin) {
        // --- 管理员设置组 ---
        m_adminGroup = new QGroupBox;
        auto *adminLayout = new QVBoxLayout(m_adminGroup);

        // 聊天室头像上传
        auto *avatarUploadLayout = new QHBoxLayout;
        m_roomAvatarLabel = new QLabel;
        avatarUploadLayout->addWidget(m_roomAvatarLabel);
        avatarUploadLayout->addStretch();
        m_uploadAvatarButton = new QPushButton;
        connect(m_uploadAvatarButton, &QPushButton::clicked,
                this, &RoomSettingsDialog::onUploadAvatar);
        avatarUploadLayout->addWidget(m_uploadAvatarButton);
        adminLayout->addLayout(avatarUploadLayout);

        // 聊天室名称
        auto *nameLayout = new QHBoxLayout;
        m_nameLabel = new QLabel;
        nameLayout->addWidget(m_nameLabel);
        m_nameEdit = new QLineEdit(roomName);
        nameLayout->addWidget(m_nameEdit, 1);
        m_saveNameButton = new QPushButton;
        connect(m_saveNameButton, &QPushButton::clicked,
                this, &RoomSettingsDialog::onSaveName);
        nameLayout->addWidget(m_saveNameButton);
        adminLayout->addLayout(nameLayout);

        // 密码设置
        auto *pwdLayout = new QHBoxLayout;
        m_passwordLabel = new QLabel;
        pwdLayout->addWidget(m_passwordLabel);
        m_passwordEdit = new QLineEdit;
        m_passwordEdit->setEchoMode(QLineEdit::Password);
        pwdLayout->addWidget(m_passwordEdit, 1);
        m_setPasswordButton = new QPushButton;
        connect(m_setPasswordButton, &QPushButton::clicked,
                this, &RoomSettingsDialog::onSetPassword);
        pwdLayout->addWidget(m_setPasswordButton);
        m_viewPasswordButton = new QPushButton;
        connect(m_viewPasswordButton, &QPushButton::clicked,
                this, &RoomSettingsDialog::onViewPassword);
        pwdLayout->addWidget(m_viewPasswordButton);
        adminLayout->addLayout(pwdLayout);

        mainLayout->addWidget(m_adminGroup);
        mainLayout->addSpacing(8);
    }

    // --- 底部按钮 ---
    auto *btnLayout = new QHBoxLayout;

    m_leaveButton = new QPushButton;
    m_leaveButton->setStyleSheet("QPushButton { color: #e67e22; }");
    connect(m_leaveButton, &QPushButton::clicked, this, [this] {
        const auto &copy = WindowsLocaleCatalog::messages(m_locale);
        if (QMessageBox::question(this, copy.roomLeave,
                copy.roomLeaveQuestion.arg(m_roomName))
            == QMessageBox::Yes) {
            emit leaveRoomRequested(m_roomId);
            accept();
        }
    });
    btnLayout->addWidget(m_leaveButton);

    if (isAdmin) {
        m_deleteButton = new QPushButton;
        m_deleteButton->setStyleSheet("QPushButton { color: red; }");
        connect(m_deleteButton, &QPushButton::clicked, this, [this] {
            const auto &copy = WindowsLocaleCatalog::messages(m_locale);
            QString input = QInputDialog::getText(
                this, copy.roomDeleteConfirmTitle,
                copy.roomDeleteConfirmPrompt.arg(m_roomName));
            if (input.trimmed() != m_roomName) {
                if (!input.isEmpty())
                    QMessageBox::warning(this, copy.roomDeleteFailedTitle,
                                         copy.roomDeleteNameMismatch);
                return;
            }
            emit deleteRoomRequested(m_roomId, m_roomName);
            accept();
        });
        btnLayout->addWidget(m_deleteButton);
    }

    btnLayout->addStretch();
    m_closeButton = new QPushButton;
    connect(m_closeButton, &QPushButton::clicked, this, &QDialog::close);
    btnLayout->addWidget(m_closeButton);

    mainLayout->addLayout(btnLayout);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &RoomSettingsDialog::applyLocale);
    }
    applyLocale();
}

void RoomSettingsDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.roomSettingsTitle);
    m_avatarPreview->setAccessibleName(copy.roomAvatar);
    m_limitsGroup->setTitle(copy.roomCurrentLimits);
    m_maxFileSizeLabel->setText(copy.roomMaxSingleFile);
    m_totalFileSpaceLabel->setText(copy.roomTotalFileSpace);
    m_maxFileCountLabel->setText(copy.roomMaxFileCount);
    m_maxMembersLabel->setText(copy.roomMaxMembers);
    m_limitsEditGroup->setTitle(copy.roomLimitSettings);
    m_editMaxFileSizeLabel->setText(copy.roomMaxSingleFileGb);
    m_editMaxFileSizeLabel->setBuddy(m_fileSizeSpin);
    m_editTotalFileSpaceLabel->setText(copy.roomTotalFileSpaceGb);
    m_editTotalFileSpaceLabel->setBuddy(m_totalSpaceSpin);
    m_editFileCountLabel->setText(copy.roomMaxFileCount);
    m_editFileCountLabel->setBuddy(m_fileCountSpin);
    m_editMemberLimitLabel->setText(copy.roomMaxMembers);
    m_editMemberLimitLabel->setBuddy(m_memberLimitSpin);
    m_developerKeyLabel->setText(copy.roomDeveloperKey);
    m_developerKeyLabel->setBuddy(m_developerKeyEdit);
    m_developerKeyEdit->setPlaceholderText(copy.roomDeveloperKeyPlaceholder);
    m_saveLimitsButton->setText(copy.roomSaveLimits);
    if (m_adminGroup) {
        m_adminGroup->setTitle(copy.roomAdministratorSettings);
        m_roomAvatarLabel->setText(copy.roomAvatar);
        m_uploadAvatarButton->setText(copy.roomChooseImage);
        m_nameLabel->setText(copy.roomName);
        m_nameLabel->setBuddy(m_nameEdit);
        m_saveNameButton->setText(copy.roomSave);
        m_passwordLabel->setText(copy.roomPassword);
        m_passwordLabel->setBuddy(m_passwordEdit);
        m_passwordEdit->setPlaceholderText(copy.roomPasswordPlaceholder);
        m_setPasswordButton->setText(copy.roomSetPassword);
        m_viewPasswordButton->setText(copy.roomViewPassword);
        m_deleteButton->setText(copy.roomDelete);
    }
    m_leaveButton->setText(copy.roomLeave);
    m_closeButton->setText(copy.roomClose);
}

void RoomSettingsDialog::onSaveName() {
    QString newName = m_nameEdit->text().trimmed();
    if (newName.isEmpty()) {
        const auto &copy = WindowsLocaleCatalog::messages(m_locale);
        QMessageBox::warning(this, copy.roomErrorTitle, copy.roomNameRequired);
        return;
    }
    if (newName == m_roomName) return;

    QJsonObject data;
    data["roomId"]  = m_roomId;
    data["newName"] = newName;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_REQ, data));
    m_roomName = newName;
}

void RoomSettingsDialog::setRoomName(const QString &roomName) {
    m_roomName = roomName;
    if (m_roomNameLabel) m_roomNameLabel->setText(roomName);
    if (m_nameEdit) m_nameEdit->setText(roomName);
}

void RoomSettingsDialog::onSaveLimits() {
    const QString developerKey = m_developerKeyEdit ? m_developerKeyEdit->text().trimmed() : QString();
    if (developerKey.isEmpty()) {
        const auto &copy = WindowsLocaleCatalog::messages(m_locale);
        QMessageBox::warning(
            this, copy.roomLimitErrorTitle, copy.roomDeveloperKeyRequired);
        return;
    }

    double sizeGB = m_fileSizeSpin->value();
    double totalGB = m_totalSpaceSpin->value();
    qint64 sizeBytes = static_cast<qint64>(sizeGB * 1024 * 1024 * 1024);
    qint64 totalBytes = static_cast<qint64>(totalGB * 1024 * 1024 * 1024);
    int fileCount = m_fileCountSpin->value();
    int maxMembers = m_memberLimitSpin->value();

    if (totalBytes < sizeBytes) {
        const auto &copy = WindowsLocaleCatalog::messages(m_locale);
        QMessageBox::warning(
            this, copy.roomLimitErrorTitle, copy.roomTotalSpaceTooSmall);
        return;
    }

    QJsonObject data;
    data["roomId"]         = m_roomId;
    data["maxFileSize"]    = static_cast<double>(sizeBytes);
    data["totalFileSpace"] = static_cast<double>(totalBytes);
    data["maxFileCount"]   = fileCount;
    data["maxMembers"]     = maxMembers;
    data["developerKey"]   = developerKey;
    emit roomLimitsSaveRequested(m_roomId);
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, data));
    m_developerKeyEdit->clear();
}

void RoomSettingsDialog::onSetPassword() {
    QString password = m_passwordEdit->text();

    QJsonObject data;
    data["roomId"]   = m_roomId;
    data["password"] = password;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_REQ, data));
    m_passwordEdit->clear();
}

void RoomSettingsDialog::onViewPassword() {
    QJsonObject data;
    data["roomId"] = m_roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::GET_ROOM_PASSWORD_REQ, data));
}

void RoomSettingsDialog::onUploadAvatar() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    QString filePath = QFileDialog::getOpenFileName(this,
        copy.roomChooseAvatar, QString(), copy.roomImageFiles);
    if (filePath.isEmpty()) return;

    QPixmap img(filePath);
    if (img.isNull()) {
        QMessageBox::warning(this, copy.roomErrorTitle, copy.roomCannotLoadImage);
        return;
    }

    AvatarCropDialog dlg(img, this, m_localeViewModel);
    if (dlg.exec() != QDialog::Accepted) return;

    QPixmap cropped = dlg.croppedAvatar();
    if (cropped.isNull()) return;

    // 转为 PNG 字节数据
    QByteArray pngData;
    QBuffer buf(&pngData);
    buf.open(QIODevice::WriteOnly);
    cropped.save(&buf, "PNG");
    buf.close();

    if (pngData.size() > 256 * 1024) {
        QMessageBox::warning(this, copy.roomNoticeTitle, copy.roomAvatarTooLarge);
        return;
    }

    // 发送上传请求
    QJsonObject data;
    data["roomId"] = m_roomId;
    data["avatarData"] = QString::fromLatin1(pngData.toBase64());
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_REQ, data));

    // 更新预览
    m_avatarPreview->setPixmap(cropped.scaled(48, 48, Qt::KeepAspectRatio, Qt::SmoothTransformation));
}
