#pragma once

#include <QDialog>
#include "WindowsLocaleCatalog.h"

class QLabel;
class QLineEdit;
class QPushButton;
class QCheckBox;
class QGroupBox;
class WindowsBandwidthViewModel;
class WindowsLocaleViewModel;

/// 个人信息修改对话框
class ProfileDialog : public QDialog {
    Q_OBJECT
public:
    explicit ProfileDialog(int userId, const QString &username,
                           const QString &displayName, const QPixmap &avatar,
                           QWidget *parent = nullptr,
                           WindowsBandwidthViewModel *bandwidthViewModel = nullptr,
                           WindowsLocaleViewModel *localeViewModel = nullptr);
    QCheckBox *lowBandwidthForTest() const { return m_lowBandwidth; }
    QLabel *bandwidthStatusForTest() const { return m_bandwidthStatus; }

signals:
    /// 用户请求更换头像（ChatWindow 触发文件选择流程）
    void changeAvatarRequested();

public slots:
    /// 外部更新头像显示（上传成功后调用）
    void updateAvatar(const QPixmap &avatar);
    /// 外部更新昵称显示
    void updateDisplayName(const QString &name);
    /// 外部更新用户ID显示
    void updateUid(const QString &uid);

private slots:
    void onSaveNickname();
    void onSaveUid();
    void onChangePassword();

private:
    void applyLocale();
    int     m_userId;
    QString m_username;
    QString m_displayName;

    QLabel      *m_avatarLabel    = nullptr;
    QLineEdit   *m_nicknameEdit   = nullptr;
    QLineEdit   *m_uidEdit        = nullptr;
    QLineEdit   *m_oldPwdEdit     = nullptr;
    QLineEdit   *m_newPwdEdit     = nullptr;
    QLineEdit   *m_confirmPwdEdit = nullptr;
    WindowsBandwidthViewModel *m_bandwidthViewModel = nullptr;
    QCheckBox   *m_lowBandwidth = nullptr;
    QLabel      *m_bandwidthStatus = nullptr;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
    QGroupBox *m_avatarGroup = nullptr;
    QGroupBox *m_infoGroup = nullptr;
    QGroupBox *m_bandwidthGroup = nullptr;
    QGroupBox *m_passwordGroup = nullptr;
    QLabel *m_nicknameLabel = nullptr;
    QLabel *m_uidLabel = nullptr;
    QLabel *m_uidHint = nullptr;
    QLabel *m_bandwidthDescription = nullptr;
    QLabel *m_oldPasswordLabel = nullptr;
    QLabel *m_newPasswordLabel = nullptr;
    QLabel *m_confirmPasswordLabel = nullptr;
    QPushButton *m_changeAvatar = nullptr;
    QPushButton *m_saveNickname = nullptr;
    QPushButton *m_saveUid = nullptr;
    QPushButton *m_changePassword = nullptr;
    QPushButton *m_close = nullptr;
};
