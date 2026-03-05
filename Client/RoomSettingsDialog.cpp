#include "RoomSettingsDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QLabel>
#include <QLineEdit>
#include <QDoubleSpinBox>
#include <QPushButton>
#include <QGroupBox>
#include <QMessageBox>
#include <QInputDialog>
#include <QFileDialog>
#include <QBuffer>
#include <QPixmap>
#include <QImage>

RoomSettingsDialog::RoomSettingsDialog(int roomId, const QString &roomName,
                                       bool isAdmin, qint64 maxFileSize,
                                       QWidget *parent)
    : QDialog(parent), m_roomId(roomId), m_roomName(roomName), m_isAdmin(isAdmin)
{
    setWindowTitle(QStringLiteral("聊天室设置"));
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

        // 文件大小上限
        auto *fileLayout = new QHBoxLayout;
        fileLayout->addWidget(new QLabel(QStringLiteral("文件上限(MB)：")));
        m_fileSizeSpin = new QDoubleSpinBox;
        m_fileSizeSpin->setRange(0.0, 4096.0);
        m_fileSizeSpin->setDecimals(0);
        m_fileSizeSpin->setSuffix(" MB");
        m_fileSizeSpin->setSpecialValueText(QStringLiteral("无限制"));
        double currentMB = maxFileSize / (1024.0 * 1024.0);
        m_fileSizeSpin->setValue(currentMB);
        fileLayout->addWidget(m_fileSizeSpin, 1);
        auto *saveFileBtn = new QPushButton(QStringLiteral("保存"));
        connect(saveFileBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onSaveFileSize);
        fileLayout->addWidget(saveFileBtn);
        adminLayout->addLayout(fileLayout);

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
    } else {
        // 非管理员也可以查看密码
        auto *viewPwdLayout = new QHBoxLayout;
        viewPwdLayout->addWidget(new QLabel(QStringLiteral("聊天室密码：")));
        viewPwdLayout->addStretch();
        auto *viewPwdBtn = new QPushButton(QStringLiteral("查看密码"));
        connect(viewPwdBtn, &QPushButton::clicked, this, &RoomSettingsDialog::onViewPassword);
        viewPwdLayout->addWidget(viewPwdBtn);
        mainLayout->addLayout(viewPwdLayout);
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

void RoomSettingsDialog::onSaveFileSize() {
    double sizeMB = m_fileSizeSpin->value();
    qint64 sizeBytes = static_cast<qint64>(sizeMB * 1024 * 1024);

    QJsonObject data;
    data["roomId"]      = m_roomId;
    data["maxFileSize"] = static_cast<double>(sizeBytes);
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
        QStringLiteral("图片文件 (*.png *.jpg *.jpeg *.bmp *.webp)"));
    if (filePath.isEmpty()) return;

    QImage img(filePath);
    if (img.isNull()) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("无法加载图片"));
        return;
    }

    // 缩放到 128x128
    if (img.width() > 128 || img.height() > 128) {
        img = img.scaled(128, 128, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }

    QByteArray imgData;
    QBuffer buf(&imgData);
    buf.open(QIODevice::WriteOnly);
    img.save(&buf, "PNG");
    buf.close();

    if (imgData.size() > 256 * 1024) {
        QMessageBox::warning(this, QStringLiteral("错误"), QStringLiteral("图片过大，请选择较小的图片"));
        return;
    }

    // 发送上传请求
    QJsonObject data;
    data["roomId"] = m_roomId;
    data["avatarData"] = QString::fromLatin1(imgData.toBase64());
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_UPLOAD_REQ, data));

    // 更新预览
    QPixmap pix = QPixmap::fromImage(img);
    m_avatarPreview->setPixmap(pix.scaled(48, 48, Qt::KeepAspectRatio, Qt::SmoothTransformation));
}
