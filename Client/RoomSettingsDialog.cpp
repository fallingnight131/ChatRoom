#include "RoomSettingsDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"
#include "AvatarCropDialog.h"

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
                                       QWidget *parent)
    : QDialog(parent), m_roomId(roomId), m_roomName(roomName), m_isAdmin(isAdmin)
{
    setWindowTitle(QStringLiteral("房间设置"));
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
    auto *nameDisplayLabel = new QLabel(roomName);
    nameDisplayLabel->setStyleSheet("font-weight: bold; font-size: 14px;");
    m_roomIdLabel = new QLabel(QStringLiteral("ID: ") + QString::number(roomId));
    m_roomIdLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_roomIdLabel->setStyleSheet("color: gray; font-size: 12px;");
    headerInfoLayout->addWidget(nameDisplayLabel);
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
    auto *limitsGroup = new QGroupBox(QStringLiteral("当前限制"));
    auto *limitsForm = new QFormLayout(limitsGroup);
    limitsForm->addRow(QStringLiteral("单文件最大:"),
                       new QLabel(QString("%1 GB").arg(maxFileSize / (1024.0 * 1024.0 * 1024.0), 0, 'f', 1)));
    limitsForm->addRow(QStringLiteral("总文件空间:"),
                       new QLabel(QString("%1 GB").arg(totalFileSpace / 1024 / 1024 / 1024)));
    limitsForm->addRow(QStringLiteral("文件数量上限:"),
                       new QLabel(QString::number(maxFileCount)));
    limitsForm->addRow(QStringLiteral("聊天室最大人数:"),
                       new QLabel(QString::number(maxMembers)));
    mainLayout->addWidget(limitsGroup);
    mainLayout->addSpacing(8);

    auto *limitsEditGroup = new QGroupBox(QStringLiteral("限制设置（需开发者秘钥）"));
    auto *limitsEditLayout = new QVBoxLayout(limitsEditGroup);

    auto *fileLayout = new QHBoxLayout;
    fileLayout->addWidget(new QLabel(QStringLiteral("单文件上限(GB)：")));
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
    totalLayout->addWidget(new QLabel(QStringLiteral("总文件空间(GB)：")));
    m_totalSpaceSpin = new QDoubleSpinBox;
    m_totalSpaceSpin->setRange(1.0, 10240.0);
    m_totalSpaceSpin->setDecimals(0);
    m_totalSpaceSpin->setSuffix(" GB");
    m_totalSpaceSpin->setValue(totalFileSpace / (1024.0 * 1024.0 * 1024.0));
    totalLayout->addWidget(m_totalSpaceSpin, 1);
    limitsEditLayout->addLayout(totalLayout);

    auto *countLayout = new QHBoxLayout;
    countLayout->addWidget(new QLabel(QStringLiteral("文件数量上限：")));
    m_fileCountSpin = new QSpinBox;
    m_fileCountSpin->setRange(1, 1000000);
    m_fileCountSpin->setValue(maxFileCount);
    countLayout->addWidget(m_fileCountSpin, 1);
    limitsEditLayout->addLayout(countLayout);

    auto *memberLayout = new QHBoxLayout;
    memberLayout->addWidget(new QLabel(QStringLiteral("聊天室最大人数：")));
    m_memberLimitSpin = new QSpinBox;
    m_memberLimitSpin->setRange(2, 1000000);
    m_memberLimitSpin->setValue(maxMembers);
    memberLayout->addWidget(m_memberLimitSpin, 1);
    limitsEditLayout->addLayout(memberLayout);

    auto *keyLayout = new QHBoxLayout;
    keyLayout->addWidget(new QLabel(QStringLiteral("开发者秘钥：")));
    m_developerKeyEdit = new QLineEdit;
    m_developerKeyEdit->setEchoMode(QLineEdit::Password);
    m_developerKeyEdit->setPlaceholderText(QStringLiteral("输入开发者秘钥后可保存限制"));
    keyLayout->addWidget(m_developerKeyEdit, 1);
    auto *saveFileBtn = new QPushButton(QStringLiteral("保存限制"));
    connect(saveFileBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onSaveLimits);
    keyLayout->addWidget(saveFileBtn);
    limitsEditLayout->addLayout(keyLayout);

    mainLayout->addWidget(limitsEditGroup);
    mainLayout->addSpacing(8);

    if (isAdmin) {
        // --- 管理员设置组 ---
        auto *adminGroup = new QGroupBox(QStringLiteral("管理员设置"));
        auto *adminLayout = new QVBoxLayout(adminGroup);

        // 聊天室头像上传
        auto *avatarUploadLayout = new QHBoxLayout;
        avatarUploadLayout->addWidget(new QLabel(QStringLiteral("聊天室头像：")));
        avatarUploadLayout->addStretch();
        auto *uploadAvatarBtn = new QPushButton(QStringLiteral("选择图片"));
        connect(uploadAvatarBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onUploadAvatar);
        avatarUploadLayout->addWidget(uploadAvatarBtn);
        adminLayout->addLayout(avatarUploadLayout);

        // 聊天室名称
        auto *nameLayout = new QHBoxLayout;
        nameLayout->addWidget(new QLabel(QStringLiteral("聊天室名称：")));
        m_nameEdit = new QLineEdit(roomName);
        nameLayout->addWidget(m_nameEdit, 1);
        auto *saveNameBtn = new QPushButton(QStringLiteral("保存"));
        connect(saveNameBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onSaveName);
        nameLayout->addWidget(saveNameBtn);
        adminLayout->addLayout(nameLayout);

        // 密码设置
        auto *pwdLayout = new QHBoxLayout;
        pwdLayout->addWidget(new QLabel(QStringLiteral("聊天室密码：")));
        m_passwordEdit = new QLineEdit;
        m_passwordEdit->setPlaceholderText(QStringLiteral("留空表示取消密码"));
        pwdLayout->addWidget(m_passwordEdit, 1);
        auto *setPwdBtn = new QPushButton(QStringLiteral("设置"));
        connect(setPwdBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onSetPassword);
        pwdLayout->addWidget(setPwdBtn);
        auto *viewPwdBtn = new QPushButton(QStringLiteral("查看"));
        connect(viewPwdBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onViewPassword);
        pwdLayout->addWidget(viewPwdBtn);
        adminLayout->addLayout(pwdLayout);

        mainLayout->addWidget(adminGroup);
        mainLayout->addSpacing(8);
    }

    // --- 底部按钮 ---
    auto *btnLayout = new QHBoxLayout;

    auto *leaveBtn = new QPushButton(QStringLiteral("退出聊天室"));
    leaveBtn->setStyleSheet("QPushButton { color: #e67e22; }");
    connect(leaveBtn, &QPushButton::clicked, this, [this] {
        if (QMessageBox::question(this, QStringLiteral("退出聊天室"),
                QString("确定要退出聊天室 %1 吗？").arg(m_roomName))
            == QMessageBox::Yes) {
            emit leaveRoomRequested(m_roomId);
            accept();
        }
    });
    btnLayout->addWidget(leaveBtn);

    if (isAdmin) {
        auto *deleteBtn = new QPushButton(QStringLiteral("删除聊天室"));
        deleteBtn->setStyleSheet("QPushButton { color: red; }");
        connect(deleteBtn, &QPushButton::clicked, this, [this] {
            QString input = QInputDialog::getText(this, QStringLiteral("确认删除"),
                QString("此操作不可恢复！\n请输入聊天室名称 \"%1\" 确认删除:").arg(m_roomName));
            if (input.trimmed() != m_roomName) {
                if (!input.isEmpty())
                    QMessageBox::warning(this, QStringLiteral("删除失败"),
                                         QStringLiteral("输入的名称不匹配，删除已取消"));
                return;
            }
            emit deleteRoomRequested(m_roomId, m_roomName);
            accept();
        });
        btnLayout->addWidget(deleteBtn);
    }

    btnLayout->addStretch();
    auto *closeBtn = new QPushButton(QStringLiteral("关闭"));
    connect(closeBtn, &QPushButton::clicked, this, &QDialog::close);
    btnLayout->addWidget(closeBtn);

    mainLayout->addLayout(btnLayout);
}

void RoomSettingsDialog::onSaveName() {
    QString newName = m_nameEdit->text().trimmed();
    if (newName.isEmpty()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("聊天室名称不能为空"));
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

void RoomSettingsDialog::onSaveLimits() {
    const QString developerKey = m_developerKeyEdit ? m_developerKeyEdit->text().trimmed() : QString();
    if (developerKey.isEmpty()) {
        QMessageBox::warning(this, QStringLiteral("限制错误"), QStringLiteral("请输入开发者秘钥"));
        return;
    }

    double sizeGB = m_fileSizeSpin->value();
    double totalGB = m_totalSpaceSpin->value();
    qint64 sizeBytes = static_cast<qint64>(sizeGB * 1024 * 1024 * 1024);
    qint64 totalBytes = static_cast<qint64>(totalGB * 1024 * 1024 * 1024);
    int fileCount = m_fileCountSpin->value();
    int maxMembers = m_memberLimitSpin->value();

    if (totalBytes < sizeBytes) {
        QMessageBox::warning(this, QStringLiteral("限制错误"), QStringLiteral("总文件空间不能小于单文件上限"));
        return;
    }

    QJsonObject data;
    data["roomId"]         = m_roomId;
    data["maxFileSize"]    = static_cast<double>(sizeBytes);
    data["totalFileSpace"] = static_cast<double>(totalBytes);
    data["maxFileCount"]   = fileCount;
    data["maxMembers"]     = maxMembers;
    data["developerKey"]   = developerKey;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, data));
}

void RoomSettingsDialog::onSetPassword() {
    QString password = m_passwordEdit->text();

    QJsonObject data;
    data["roomId"]   = m_roomId;
    data["password"] = password;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_REQ, data));
}

void RoomSettingsDialog::onViewPassword() {
    QJsonObject data;
    data["roomId"] = m_roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::GET_ROOM_PASSWORD_REQ, data));
}

void RoomSettingsDialog::onUploadAvatar() {
    QString filePath = QFileDialog::getOpenFileName(this,
        QStringLiteral("选择聊天室头像"), QString(),
        QStringLiteral("图片文件 (*.png *.jpg *.jpeg *.bmp *.gif)"));
    if (filePath.isEmpty()) return;

    QPixmap img(filePath);
    if (img.isNull()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("无法加载图片"));
        return;
    }

    AvatarCropDialog dlg(img, this);
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
        QMessageBox::warning(this, QStringLiteral("提示"), QStringLiteral("头像数据过大，请选择更小的图片或裁剪区域"));
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
