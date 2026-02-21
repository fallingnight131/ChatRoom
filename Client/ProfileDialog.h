#pragma once

#include <QDialog>

class QLabel;
class QLineEdit;
class QPushButton;

/// 个人信息修改对话框
class ProfileDialog : public QDialog {
    Q_OBJECT
public:
    explicit ProfileDialog(int userId, const QString &username,
                           const QString &displayName, const QPixmap &avatar,
                           QWidget *parent = nullptr);

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
    int     m_userId;
    QString m_username;
    QString m_displayName;

    QLabel      *m_avatarLabel    = nullptr;
    QLineEdit   *m_nicknameEdit   = nullptr;
    QLineEdit   *m_uidEdit        = nullptr;
    QLineEdit   *m_oldPwdEdit     = nullptr;
    QLineEdit   *m_newPwdEdit     = nullptr;
    QLineEdit   *m_confirmPwdEdit = nullptr;
};
