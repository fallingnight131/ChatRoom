#pragma once

#include <QDialog>
#include <QPixmap>

class QLabel;

/// 用户信息查看对话框
class UserInfoDialog : public QDialog {
    Q_OBJECT
public:
    explicit UserInfoDialog(const QString &username, const QString &displayName,
                            const QPixmap &avatar, const QString &role = QString(),
                            QWidget *parent = nullptr);

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void viewLargeAvatar();

    QPixmap  m_avatar;
    QLabel  *m_avatarLabel = nullptr;
};
