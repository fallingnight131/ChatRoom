#pragma once

#include <QDialog>
#include <QPixmap>
#include "WindowsLocaleCatalog.h"

class QLabel;
class QPushButton;
class WindowsLocaleViewModel;

/// 用户信息查看对话框
class UserInfoDialog : public QDialog {
    Q_OBJECT
public:
    enum class Role { None, Member, Administrator };

    explicit UserInfoDialog(const QString &username, const QString &displayName,
                            const QPixmap &avatar, Role role = Role::None,
                            QWidget *parent = nullptr,
                            WindowsLocaleViewModel *localeViewModel = nullptr);
    QLabel *avatarForTest() const { return m_avatarLabel; }
    QLabel *nicknameForTest() const { return m_nicknameLabel; }
    QLabel *idForTest() const { return m_idLabel; }
    QLabel *roleForTest() const { return m_roleLabel; }
    QPushButton *closeForTest() const { return m_closeButton; }

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private slots:
    void viewLargeAvatar();

private:
    void applyLocale();
    QString roleText() const;

    QPixmap  m_avatar;
    QLabel  *m_avatarLabel = nullptr;
    QLabel *m_nicknameLabel = nullptr;
    QLabel *m_idLabel = nullptr;
    QLabel *m_roleLabel = nullptr;
    QPushButton *m_closeButton = nullptr;
    QString m_username;
    QString m_displayName;
    Role m_role = Role::None;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
};
